package com.tazzzo.commerce.read;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.common.metrics.SnapshotCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Depth of the product-card rebuild queue (the {@code product_card_rebuild} items of the shared {@code work_queue}; other
 * item types are never counted), from a snapshot refreshed at most every {@code ttl}. Each count is capped
 * ({@value #COUNT_CAP} rows) and time-bounded ({@value #MAX_TIME_MS} ms). The filters use the {@code (status, type)} index;
 * the two "oldest" lookups are a top-1 sort over the matching rows (no index on {@code requested_at} or {@code lease_until}
 * was added), bounded by the same max time, so on a very large backlog they can time out, in which case the previous
 * snapshot is kept. Complements, never repeats, {@code tazzzo.commerce.freshness.queue.lag}, which is only observed when an
 * item is claimed and therefore says nothing about a queue nobody is draining.
 * <ul>
 *   <li>{@code projection_rebuild_queue_depth{state}}: {@code due} = PENDING plus LEASED whose lease lapsed (a crashed
 *       worker; they will be re-claimed); {@code leased} = held under a live lease.</li>
 *   <li>{@code projection_rebuild_queue_oldest_due_age_seconds}: now minus the earliest {@code requested_at} of a PENDING
 *       item or {@code lease_until} of a lapsed LEASED item, floored at 0; 0 when nothing is due.</li>
 * </ul>
 * A database error keeps the last good snapshot (NaN before the first).
 */
public class ProjectionQueueGauges {

    static final int COUNT_CAP = 100_000;
    static final long MAX_TIME_MS = 2_000;

    private record Snapshot(double due, double leased, double oldestDueAgeSeconds) { }

    private final MongoCollection<Document> queue;
    private final int cap;
    private final SnapshotCache<Snapshot> cache;

    public ProjectionQueueGauges(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl) {
        this(db, clock, registry, ttl, COUNT_CAP);
    }

    ProjectionQueueGauges(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl, int cap) {
        this.queue = db.getCollection(ProjectionRebuildQueue.COLLECTION);
        this.cap = cap;
        this.cache = new SnapshotCache<>(new Snapshot(Double.NaN, Double.NaN, Double.NaN), clock, ttl, this::load);
        Gauge.builder("projection_rebuild_queue_depth", () -> cache.get().due()).tag("state", "due")
                .description("rebuild items waiting to be claimed (pending plus lapsed lease), capped at " + cap).register(registry);
        Gauge.builder("projection_rebuild_queue_depth", () -> cache.get().leased()).tag("state", "leased")
                .description("rebuild items under a live lease, capped at " + cap).register(registry);
        Gauge.builder("projection_rebuild_queue_oldest_due_age_seconds", () -> cache.get().oldestDueAgeSeconds())
                .baseUnit("seconds").description("age of the earliest due rebuild item; 0 when none").register(registry);
    }

    private Snapshot load(Instant now) {
        CountOptions count = new CountOptions().limit(cap).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS);
        Date nowDate = Date.from(now);
        Bson mine = Filters.eq("type", ProjectionRebuildQueue.TYPE);
        Bson pending = Filters.and(mine, Filters.eq("status", "pending"));
        Bson lapsed = Filters.and(mine, Filters.eq("status", "leased"), Filters.lte("lease_until", nowDate));
        Bson live = Filters.and(mine, Filters.eq("status", "leased"), Filters.gt("lease_until", nowDate));
        long due = queue.countDocuments(pending, count) + queue.countDocuments(lapsed, count);
        long leased = queue.countDocuments(live, count);
        Document oldestPending = queue.find(pending).sort(Sorts.ascending("requested_at"))
                .projection(Projections.include("requested_at")).limit(1).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).first();
        Document oldestLapsed = queue.find(lapsed).sort(Sorts.ascending("lease_until"))
                .projection(Projections.include("lease_until")).limit(1).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).first();
        Instant earliest = null;
        if (oldestPending != null && oldestPending.getDate("requested_at") != null) {
            earliest = oldestPending.getDate("requested_at").toInstant();
        }
        if (oldestLapsed != null && oldestLapsed.getDate("lease_until") != null) {
            Instant lease = oldestLapsed.getDate("lease_until").toInstant();
            earliest = earliest == null || lease.isBefore(earliest) ? lease : earliest;
        }
        double age = earliest == null ? 0 : Math.max(0, Duration.between(earliest, now).toMillis() / 1000.0);
        return new Snapshot(due, leased, age);
    }
}
