package com.tazzzo.notification;

import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The outbox and dispatcher against real Mongo: dedupe, rollback, claim/lease, every outcome, expiry and erasure. */
@SpringBootTest(classes = CatalogApplication.class)
@Timeout(60)
class NotificationDispatcherIT extends AbstractMongoIT {

    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-06-01T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }

        void advance(Duration d) { now = now.plus(d); }
    }

    /** Answers from a script; records every notification it was handed. */
    static final class ScriptedSender implements NotificationSender {
        final Deque<Object> script = new ArrayDeque<>();
        final List<OutboxNotification> seen = new ArrayList<>();

        @Override
        public Outcome send(OutboxNotification n) {
            seen.add(n);
            Object next = script.isEmpty() ? Outcome.SENT : script.pop();
            if (next instanceof RuntimeException e) throw e;
            return (Outcome) next;
        }
    }

    MovingClock clock;
    NotificationOutbox outbox;
    ScriptedSender sender;
    SimpleMeterRegistry registry;
    NotificationDispatcher dispatcher;

    @BeforeEach
    void reset() {
        db.getCollection(NotificationOutbox.COLLECTION).deleteMany(new Document());
        clock = new MovingClock();
        outbox = new NotificationOutbox(db, clock);
        sender = new ScriptedSender();
        registry = new SimpleMeterRegistry();
        dispatcher = new NotificationDispatcher(outbox, sender, clock, registry, Duration.ofSeconds(60), Duration.ofHours(1),
                Duration.ofSeconds(30), 3);
    }

    NotificationRequest req(String subject) {
        return new NotificationRequest(NotificationType.ORDER_CONFIRMED, "CUS_n2test01", subject, Map.of("item_count", "2"));
    }

    void enqueue(String subject) {
        new Tx(client).run(s -> outbox.enqueue(s, req(subject)));
    }

    Document row(String subject) {
        return db.getCollection(NotificationOutbox.COLLECTION).find(new Document("_id", "ORDER_CONFIRMED:" + subject)).first();
    }

    double count(String outcome) {
        var c = registry.find("notification_dispatch").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void enqueue_is_idempotent_per_subject_and_rolls_back_with_its_transaction() {
        enqueue("ORD_1");
        enqueue("ORD_1");
        assertThat(db.getCollection(NotificationOutbox.COLLECTION).countDocuments()).isEqualTo(1);

        assertThatThrownBy(() -> new Tx(client).run(s -> {
            outbox.enqueue(s, req("ORD_2"));
            throw new IllegalStateException("business write failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(row("ORD_2")).isNull();
    }

    @Test
    void a_sent_notification_is_completed_once_and_never_resent() {
        enqueue("ORD_1");
        assertThat(dispatcher.dispatchDue(10)).isEqualTo(1);
        assertThat(sender.seen).hasSize(1);
        OutboxNotification n = sender.seen.get(0);
        assertThat(n.customerId()).isEqualTo("CUS_n2test01");
        assertThat(n.subjectId()).isEqualTo("ORD_1");
        assertThat(n.params()).containsEntry("item_count", "2");
        assertThat(n.attempt()).isEqualTo(1);
        Document d = row("ORD_1");
        assertThat(d.getString("status")).isEqualTo("SENT");
        assertThat(d).doesNotContainKeys("claim_token", "lease_until");

        enqueue("ORD_1");                                             // a replayed business transaction
        assertThat(row("ORD_1").getString("status")).as("a repeat never resets a sent row").isEqualTo("SENT");
        clock.advance(Duration.ofHours(2));
        assertThat(dispatcher.dispatchDue(10)).isZero();
        assertThat(sender.seen).hasSize(1);
        assertThat(count("sent")).isEqualTo(1);
    }

    @Test
    void retries_back_off_exponentially_then_fail_after_max_attempts() {
        enqueue("ORD_1");
        sender.script.addAll(List.of(NotificationSender.Outcome.RETRY, new IllegalStateException("provider down"),
                NotificationSender.Outcome.RETRY));

        dispatcher.dispatchDue(10);                                   // attempt 1 -> retry in 30 s
        Document d = row("ORD_1");
        assertThat(d.getString("status")).isEqualTo("PENDING");
        assertThat(d.getDate("next_attempt_at").toInstant()).isEqualTo(clock.now.plusSeconds(30));
        assertThat(dispatcher.dispatchDue(10)).as("not yet due").isZero();

        clock.advance(Duration.ofSeconds(30));
        dispatcher.dispatchDue(10);                                   // attempt 2 throws -> retry in 60 s
        assertThat(row("ORD_1").getDate("next_attempt_at").toInstant()).isEqualTo(clock.now.plusSeconds(60));
        clock.advance(Duration.ofSeconds(59));
        assertThat(dispatcher.dispatchDue(10)).isZero();

        clock.advance(Duration.ofSeconds(1));
        dispatcher.dispatchDue(10);                                   // attempt 3 = max -> FAILED
        assertThat(row("ORD_1").getString("status")).isEqualTo("FAILED");
        assertThat(row("ORD_1").getInteger("attempts")).isEqualTo(3);
        assertThat(sender.seen).extracting(OutboxNotification::attempt).containsExactly(1, 2, 3);
        assertThat(count("retry")).isEqualTo(2);
        assertThat(count("failed")).isEqualTo(1);
    }

    @Test
    void a_rejection_fails_at_once_and_a_stale_row_expires_unsent() {
        enqueue("ORD_1");
        sender.script.add(NotificationSender.Outcome.REJECTED);
        dispatcher.dispatchDue(10);
        assertThat(row("ORD_1").getString("status")).isEqualTo("FAILED");
        assertThat(count("rejected")).isEqualTo(1);

        enqueue("ORD_2");
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        dispatcher.dispatchDue(10);
        assertThat(row("ORD_2").getString("status")).isEqualTo("EXPIRED");
        assertThat(sender.seen).extracting(OutboxNotification::subjectId).containsExactly("ORD_1");
    }

    @Test
    void a_lapsed_lease_is_reclaimed_and_the_old_claim_can_no_longer_complete() {
        enqueue("ORD_1");
        NotificationOutbox.Claim first = outbox.claimNext(Duration.ofSeconds(60)).orElseThrow();
        assertThat(outbox.claimNext(Duration.ofSeconds(60))).as("leased").isEmpty();

        clock.advance(Duration.ofSeconds(61));
        NotificationOutbox.Claim second = outbox.claimNext(Duration.ofSeconds(60)).orElseThrow();
        assertThat(second.notification().attempt()).isEqualTo(2);
        assertThat(outbox.complete(first, "SENT")).as("stale claim").isFalse();
        assertThat(outbox.retryAt(first, clock.now)).as("stale claim").isFalse();
        assertThat(row("ORD_1").getString("status")).isEqualTo("SENDING");
        assertThat(outbox.complete(second, "SENT")).isTrue();
        assertThat(row("ORD_1").getString("status")).isEqualTo("SENT");
    }

    @Test
    void a_tick_claims_at_most_its_batch_oldest_first() {
        for (int i = 1; i <= 5; i++) {
            enqueue("ORD_" + i);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(dispatcher.dispatchDue(2)).isEqualTo(2);
        assertThat(sender.seen).extracting(OutboxNotification::subjectId).containsExactly("ORD_1", "ORD_2");
        assertThat(dispatcher.dispatchDue(10)).isEqualTo(3);
    }

    @Test
    void erasure_removes_every_row_for_the_customer_only() {
        enqueue("ORD_1");
        new Tx(client).run(s -> outbox.enqueue(s, new NotificationRequest(NotificationType.ORDER_CONFIRMED, "CUS_other001",
                "ORD_9", Map.of())));
        Long erased = new Tx(client).call(s -> new NotificationErasure(db).erase(s, "CUS_n2test01"));
        assertThat(erased).isEqualTo(1L);
        assertThat(row("ORD_1")).isNull();
        assertThat(row("ORD_9")).isNotNull();
    }

    @Test
    void dispatch_refuses_to_start_without_a_provider_or_with_bad_settings() {
        assertThatThrownBy(() -> new NotificationDispatcher(outbox, new DisabledNotificationSender(), clock, registry,
                Duration.ofSeconds(60), Duration.ofHours(1), Duration.ofSeconds(30), 3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no provider");
        assertThatThrownBy(() -> new NotificationDispatcher(outbox, sender, clock, registry,
                Duration.ZERO, Duration.ofHours(1), Duration.ofSeconds(30), 3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new NotificationDispatcher(outbox, sender, clock, registry,
                Duration.ofSeconds(60), Duration.ofHours(1), Duration.ofSeconds(30), 0)).isInstanceOf(IllegalStateException.class);
        assertThat(dispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(dispatcher.backoff(4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(dispatcher.backoff(30)).isEqualTo(NotificationDispatcher.MAX_BACKOFF);
    }
}
