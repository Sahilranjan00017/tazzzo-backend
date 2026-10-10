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
 * ({@value #COUNT_CAP} rows) and time-bounded ({@value #MAX_TIME_MS} ms). The counts use the {@code (status, type)} index; the
 * two "oldest" lookups are top-1 ordered walks of the partial indexes created by migration V0018, and are cached and fail
 * independently of the counts and of each other (a failing lookup keeps its own last value; the age reads NaN until both
 * lookups have succeeded once). Complements, never repeats, {@code tazzzo.commerce.freshness.queue.lag}, which is only observed when an
 * item is claimed and therefore says nothing about a queue nobody is draining.
 * <ul>
 *   <li>{@code projection_rebuild_queue_depth{state}}: {@code due} = PENDING plus LEASED whose lease lapsed (a crashed
 *       worker; they will be re-claimed); {@code leased} = held under a live lease.</li>
 *   <li>{@code projection_rebuild_queue_oldest_due_age_seconds}: now minus the earliest {@code requested_at} of a PENDING
 *       item or {@code lease_until} of a lapsed LEASED item, floored at 0; 0 when nothing is due.</li>
 * </ul>
 * A database error keeps the last good value of whatever failed (NaN before its first success).
 */
public class ProjectionQueueGauges {

    static final int COUNT_CAP = 100_000;
    static final long MAX_TIME_MS = 2_000;

    private record Counts(double due, double leased) { }

    /** The earliest timestamp of one lookup; {@code known=false} until that lookup first succeeded. */
    private record Oldest(boolean known, Instant at) { }

    private final MongoCollection<Document> queue;
    private final int cap;
    private final Clock clock;
    // Three independent snapshots: a lookup that times out (the top-1 lookups are the expensive ones on a huge backlog) keeps ITS
    // last value and never discards the cheap counts or the other lookup.
    private final SnapshotCache<Counts> counts;
    private final SnapshotCache<Oldest> oldestPending;
    private final SnapshotCache<Oldest> oldestLapsed;

    public ProjectionQueueGauges(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl) {
        this(db, clock, registry, ttl, COUNT_CAP);
    }

    ProjectionQueueGauges(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl, int cap) {
        this.queue = db.getCollection(ProjectionRebuildQueue.COLLECTION);
        this.cap = cap;
        this.clock = clock;
        Oldest unknown = new Oldest(false, null);
        this.counts = new SnapshotCache<>(new Counts(Double.NaN, Double.NaN), clock, ttl, this::loadCounts);
        this.oldestPending = new SnapshotCache<>(unknown, clock, ttl, now -> loadOldest(pending(), "requested_at"));
        this.oldestLapsed = new SnapshotCache<>(unknown, clock, ttl, now -> loadOldest(lapsed(now), "lease_until"));
        Gauge.builder("projection_rebuild_queue_depth", () -> counts.get().due()).tag("state", "due")
                .description("rebuild items waiting to be claimed (pending plus lapsed lease), capped at " + cap).register(registry);
        Gauge.builder("projection_rebuild_queue_depth", () -> counts.get().leased()).tag("state", "leased")
                .description("rebuild items under a live lease, capped at " + cap).register(registry);
        Gauge.builder("projection_rebuild_queue_oldest_due_age_seconds", this::oldestAge)
                .baseUnit("seconds").description("age of the earliest due rebuild item; 0 when none").register(registry);
    }

    private static Bson mine() {
        return Filters.eq("type", ProjectionRebuildQueue.TYPE);
    }

    private static Bson pending() {
        return Filters.and(mine(), Filters.eq("status", "pending"));
    }

    private static Bson lapsed(Instant now) {
        return Filters.and(mine(), Filters.eq("status", "leased"), Filters.lte("lease_until", Date.from(now)));
    }

    private Counts loadCounts(Instant now) {
        CountOptions count = new CountOptions().limit(cap).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS);
        Bson live = Filters.and(mine(), Filters.eq("status", "leased"), Filters.gt("lease_until", Date.from(now)));
        long due = queue.countDocuments(pending(), count) + queue.countDocuments(lapsed(now), count);
        long leased = queue.countDocuments(live, count);
        return new Counts(due, leased);
    }

    /** Top-1 by the supporting partial index (V0018): an ordered index walk, never a sort of the backlog. */
    private Oldest loadOldest(Bson filter, String field) {
        Document d = queue.find(filter).sort(Sorts.ascending(field)).projection(Projections.include(field)).limit(1)
                .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).first();
        Date at = d == null ? null : d.getDate(field);
        return new Oldest(true, at == null ? null : at.toInstant());
    }

    private double oldestAge() {
        Oldest p = oldestPending.get();
        Oldest l = oldestLapsed.get();
        if (!p.known() || !l.known()) {
            return Double.NaN;
        }
        Instant earliest = p.at();
        if (l.at() != null && (earliest == null || l.at().isBefore(earliest))) {
            earliest = l.at();
        }
        return earliest == null ? 0 : Math.max(0, Duration.between(earliest, clock.instant()).toMillis() / 1000.0);
    }
}
