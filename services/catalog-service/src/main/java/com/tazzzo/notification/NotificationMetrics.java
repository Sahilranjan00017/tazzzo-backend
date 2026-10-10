package com.tazzzo.notification;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox health gauges, untagged (no cardinality) and served from a snapshot refreshed at most every {@code ttl}, so a
 * scrape never costs more than a handful of bounded queries per interval however often it runs. Each query is capped
 * ({@link #COUNT_CAP} rows, {@link #MAX_TIME_MS} ms). The refresh runs outside any lock; one caller refreshes while
 * concurrent scrapes keep reading the previous snapshot.
 * <ul>
 *   <li>{@code notification_outbox_pending}: PENDING rows plus SENDING rows whose lease has lapsed (a crashed dispatcher;
 *       they are due for re-claim), each count capped at 100000.</li>
 *   <li>{@code notification_outbox_failed}: terminal FAILED rows still within retention, capped at 100000.</li>
 *   <li>{@code notification_outbox_oldest_pending_age_seconds}: now minus the earliest due time among those rows
 *       ({@code next_attempt_at} of PENDING, {@code lease_until} of lapsed SENDING), floored at 0; 0 when none.</li>
 * </ul>
 * Index use: PENDING and FAILED use the {@code status} prefix of {@code notification_due} (and its {@code next_attempt_at}
 * order for the oldest). There is no index on {@code lease_until}, so the lapsed-SENDING queries walk the {@code status=SENDING}
 * prefix and filter the (normally batch-size or fewer) in-flight rows; they are bounded by the cap and the max time, and no
 * index was added for them. A database or other error keeps the last good snapshot (NaN before the first).
 */
public class NotificationMetrics {

    static final int COUNT_CAP = 100_000;
    static final long MAX_TIME_MS = 2_000;

    private final MongoCollection<Document> rows;
    private final Clock clock;
    private final Duration ttl;
    private final int cap;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile State state = new State(new Snapshot(Double.NaN, Double.NaN, Double.NaN), null);

    private record Snapshot(double pending, double failed, double oldestPendingAgeSeconds) { }

    private record State(Snapshot snapshot, Instant loadedAt) { }

    public NotificationMetrics(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl) {
        this(db, clock, registry, ttl, COUNT_CAP);
    }

    NotificationMetrics(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl, int cap) {
        this.rows = db.getCollection(NotificationOutbox.COLLECTION);
        this.clock = clock;
        this.ttl = ttl;
        this.cap = cap;
        Gauge.builder("notification_outbox_pending", () -> current().pending())
                .description("PENDING plus lapsed-lease SENDING outbox rows, each count capped at " + cap).register(registry);
        Gauge.builder("notification_outbox_failed", () -> current().failed())
                .description("terminal FAILED outbox rows within retention, capped at " + cap).register(registry);
        Gauge.builder("notification_outbox_oldest_pending_age_seconds", () -> current().oldestPendingAgeSeconds())
                .baseUnit("seconds")
                .description("age of the earliest due PENDING or lapsed-lease SENDING row; 0 when none").register(registry);
    }

    private Snapshot current() {
        State s = state;
        Instant now = clock.instant();
        boolean stale = s.loadedAt() == null || Duration.between(s.loadedAt(), now).compareTo(ttl) >= 0
                || now.isBefore(s.loadedAt());
        if (stale && refreshing.compareAndSet(false, true)) {
            try {
                Snapshot next = s.snapshot();
                try {
                    next = load(now);
                } catch (RuntimeException e) {
                    // keep the last good snapshot; loadedAt still advances so a failing database is retried once per ttl
                }
                state = new State(next, now);
            } finally {
                refreshing.set(false);
            }
        }
        return state.snapshot();
    }

    private Snapshot load(Instant now) {
        CountOptions count = new CountOptions().limit(cap).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS);
        Date nowDate = Date.from(now);
        var lapsed = Filters.and(Filters.eq("status", "SENDING"), Filters.lte("lease_until", nowDate));
        long pending = rows.countDocuments(Filters.eq("status", "PENDING"), count) + rows.countDocuments(lapsed, count);
        long failed = rows.countDocuments(Filters.eq("status", "FAILED"), count);
        Document due = rows.find(Filters.eq("status", "PENDING")).sort(Sorts.ascending("next_attempt_at", "_id"))
                .projection(Projections.include("next_attempt_at")).limit(1).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).first();
        Document stuck = rows.find(lapsed).sort(Sorts.ascending("lease_until"))
                .projection(Projections.include("lease_until")).limit(1).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).first();
        Instant earliest = null;
        if (due != null && due.getDate("next_attempt_at") != null) {
            earliest = due.getDate("next_attempt_at").toInstant();
        }
        if (stuck != null && stuck.getDate("lease_until") != null) {
            Instant lease = stuck.getDate("lease_until").toInstant();
            earliest = earliest == null || lease.isBefore(earliest) ? lease : earliest;
        }
        double age = earliest == null ? 0 : Math.max(0, Duration.between(earliest, now).toMillis() / 1000.0);
        return new Snapshot(pending, failed, age);
    }
}
