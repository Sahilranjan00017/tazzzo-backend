package com.tazzzo.notification;

import com.mongodb.MongoException;
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

/**
 * Outbox health gauges, untagged (no cardinality) and served from a snapshot refreshed at most every {@code ttl}, so a
 * scrape never costs more than three index-backed, bounded queries per interval however often it runs.
 * <ul>
 *   <li>{@code notification_outbox_pending}: PENDING rows (count capped at {@link #COUNT_CAP}; index {@code notification_due}).</li>
 *   <li>{@code notification_outbox_failed}: terminal FAILED rows still within retention (same cap; index prefix {@code status}).</li>
 *   <li>{@code notification_outbox_oldest_pending_age_seconds}: now minus the earliest {@code next_attempt_at} among PENDING
 *       rows, floored at 0 (0 when none): how far behind the dispatcher is. A row waiting out a backoff is not behind.</li>
 * </ul>
 * A database error keeps the last good snapshot (NaN before the first one).
 */
public class NotificationMetrics {

    static final int COUNT_CAP = 100_000;

    private final MongoCollection<Document> rows;
    private final Clock clock;
    private final Duration ttl;
    private Snapshot snapshot = new Snapshot(Double.NaN, Double.NaN, Double.NaN);
    private Instant loadedAt;

    private record Snapshot(double pending, double failed, double oldestPendingAgeSeconds) { }

    public NotificationMetrics(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl) {
        this.rows = db.getCollection(NotificationOutbox.COLLECTION);
        this.clock = clock;
        this.ttl = ttl;
        Gauge.builder("notification_outbox_pending", () -> current().pending())
                .description("PENDING outbox rows").register(registry);
        Gauge.builder("notification_outbox_failed", () -> current().failed())
                .description("terminal FAILED outbox rows within retention").register(registry);
        Gauge.builder("notification_outbox_oldest_pending_age_seconds", () -> current().oldestPendingAgeSeconds())
                .baseUnit("seconds").description("age of the earliest due PENDING row; 0 when none").register(registry);
    }

    private synchronized Snapshot current() {
        Instant now = clock.instant();
        if (loadedAt == null || Duration.between(loadedAt, now).compareTo(ttl) >= 0 || now.isBefore(loadedAt)) {
            try {
                snapshot = load(now);
            } catch (MongoException e) {
                // keep the last good snapshot
            }
            loadedAt = now;
        }
        return snapshot;
    }

    private Snapshot load(Instant now) {
        CountOptions cap = new CountOptions().limit(COUNT_CAP);
        long pending = rows.countDocuments(Filters.eq("status", "PENDING"), cap);
        long failed = rows.countDocuments(Filters.eq("status", "FAILED"), cap);
        Document oldest = rows.find(Filters.eq("status", "PENDING")).sort(Sorts.ascending("next_attempt_at", "_id"))
                .projection(Projections.include("next_attempt_at")).limit(1).first();
        double age = 0;
        if (oldest != null) {
            Date due = oldest.getDate("next_attempt_at");
            if (due != null) {
                age = Math.max(0, Duration.between(due.toInstant(), now).toMillis() / 1000.0);
            }
        }
        return new Snapshot(pending, failed, age);
    }
}
