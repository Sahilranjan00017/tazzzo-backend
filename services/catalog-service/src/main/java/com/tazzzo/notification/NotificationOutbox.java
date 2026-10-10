package com.tazzzo.notification;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code notification_outbox} collection. {@code _id} is the dedupe key ({@code TYPE:subject}); a row is PENDING until
 * a dispatcher claims it (SENDING, with a lease and a fresh claim token), then SENT, FAILED or EXPIRED. Every completion is
 * conditional on the claim token, so a worker whose lease lapsed can never overwrite the outcome of the worker that took
 * over. Every row carries {@code expire_at} (created + 7 days) and the TTL index removes it whatever its state.
 */
public class NotificationOutbox implements NotificationEnqueuer {

    public static final String COLLECTION = "notification_outbox";
    static final Duration RETENTION = Duration.ofDays(7);

    private final MongoDatabase db;
    private final Clock clock;
    private final MeterRegistry registry;

    public NotificationOutbox(MongoDatabase db, Clock clock) {
        this(db, clock, null);
    }

    public NotificationOutbox(MongoDatabase db, Clock clock, MeterRegistry registry) {
        this.db = db;
        this.clock = clock;
        this.registry = registry;
    }

    private MongoCollection<Document> rows() {
        return db.getCollection(COLLECTION);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Upsert with {@code $setOnInsert}: a second enqueue of the same (type, subject) is a no-op, and unlike an insert it
     * never raises a duplicate-key error, which would abort the caller's whole transaction.
     */
    @Override
    public void enqueue(ClientSession session, NotificationRequest r) {
        Instant now = now();
        Document insert = new Document("type", r.type().name())
                .append("customer_id", r.customerId())
                .append("subject_id", r.subjectId())
                .append("params", new Document(new LinkedHashMap<>(r.params())))
                .append("status", "PENDING")
                .append("attempts", 0)
                .append("next_attempt_at", now)
                .append("created_at", now)
                .append("expire_at", now.plus(RETENTION));
        var result = rows().updateOne(session, Filters.eq("_id", r.dedupeKey()), new Document("$setOnInsert", insert),
                new UpdateOptions().upsert(true));
        if (result.getUpsertedId() != null) {
            countEnqueued(r.type());
        }
    }

    /**
     * {@code notification_enqueued{type}}: a NEW row was written (a deduped repeat is not counted). It is recorded at write
     * time inside the caller's transaction, so a transaction that later rolls back can leave it one too high.
     */
    private void countEnqueued(NotificationType type) {
        if (registry == null) {
            return;
        }
        try {
            Counter.builder("notification_enqueued").tag("type", type.name()).register(registry).increment();
        } catch (RuntimeException e) {
            // instrumentation never changes a business outcome
        }
    }

    /** A claimed row plus the token that every completion must present. */
    public record Claim(OutboxNotification notification, String token) { }

    /** Atomically claims the oldest due row (PENDING and due, or SENDING with a lapsed lease). */
    public Optional<Claim> claimNext(Duration lease) {
        Instant now = now();
        String token = UUID.randomUUID().toString();
        Bson due = Filters.or(
                Filters.and(Filters.eq("status", "PENDING"), Filters.lte("next_attempt_at", now)),
                Filters.and(Filters.eq("status", "SENDING"), Filters.lte("lease_until", now)));
        Document d = rows().findOneAndUpdate(due,
                Updates.combine(Updates.set("status", "SENDING"), Updates.set("lease_until", now.plus(lease)),
                        Updates.set("claim_token", token), Updates.inc("attempts", 1)),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("next_attempt_at", "_id")).returnDocument(ReturnDocument.AFTER));
        if (d == null) {
            return Optional.empty();
        }
        Map<String, String> params = new LinkedHashMap<>();
        Document p = d.get("params", Document.class);
        if (p != null) {
            p.forEach((k, v) -> params.put(k, String.valueOf(v)));
        }
        return Optional.of(new Claim(new OutboxNotification(d.getString("_id"), NotificationType.valueOf(d.getString("type")),
                d.getString("customer_id"), d.getString("subject_id"), Map.copyOf(params), d.getInteger("attempts"),
                d.getDate("created_at").toInstant()), token));
    }

    /** @return false when the claim was lost (lease lapsed and another worker took the row). */
    public boolean complete(Claim c, String status) {
        return finish(c, Updates.combine(Updates.set("status", status), Updates.set("completed_at", now())));
    }

    public boolean retryAt(Claim c, Instant next) {
        return finish(c, Updates.combine(Updates.set("status", "PENDING"), Updates.set("next_attempt_at", next)));
    }

    private boolean finish(Claim c, Bson update) {
        return rows().updateOne(Filters.and(Filters.eq("_id", c.notification().id()), Filters.eq("status", "SENDING"),
                Filters.eq("claim_token", c.token())),
                Updates.combine(update, Updates.unset("lease_until"), Updates.unset("claim_token"))).getModifiedCount() == 1;
    }

}
