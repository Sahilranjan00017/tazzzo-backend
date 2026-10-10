package com.tazzzo.bulkimport.jobs;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.tazzzo.common.metrics.SnapshotCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Import backlog gauges, served from a snapshot refreshed at most every {@code ttl} (see {@link SnapshotCache}); each query
 * is capped ({@value #COUNT_CAP} rows) and time-bounded ({@value #MAX_TIME_MS} ms), all on the {@code status} prefix of the
 * {@code import_jobs_claim} index, and the set they walk is the non-terminal jobs, which {@code tazzzo.imports.max-active-jobs}
 * bounds.
 * <ul>
 *   <li>{@code import_jobs_active{status}}: non-terminal jobs per status. The tag is the closed set OPEN, VALIDATING,
 *       VALIDATED, REJECTED, APPLYING, PAUSED (terminal COMPLETED and CANCELLED are history, not backlog).</li>
 *   <li>{@code import_job_oldest_active_age_seconds}: how long the most neglected worker-owned job (VALIDATING or APPLYING)
 *       has gone without progress: now minus the smallest {@code updated_at}, which every claim, lease renewal and recorded
 *       batch advances. 0 when the worker owns nothing. A job that is merely long-running keeps this near 0; a stalled or
 *       absent worker lets it grow.</li>
 * </ul>
 * A database error keeps the last good snapshot (NaN before the first).
 */
public class ImportJobGauges {

    static final int COUNT_CAP = 10_000;
    static final long MAX_TIME_MS = 2_000;
    static final List<ImportJob.Status> BACKLOG = List.of(ImportJob.Status.OPEN, ImportJob.Status.VALIDATING,
            ImportJob.Status.VALIDATED, ImportJob.Status.REJECTED, ImportJob.Status.APPLYING, ImportJob.Status.PAUSED);

    private record Snapshot(Map<ImportJob.Status, Double> perStatus, double oldestAgeSeconds) { }

    private final MongoCollection<Document> jobs;
    private final int cap;
    private final SnapshotCache<Snapshot> cache;

    public ImportJobGauges(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl) {
        this(db, clock, registry, ttl, COUNT_CAP);
    }

    ImportJobGauges(MongoDatabase db, Clock clock, MeterRegistry registry, Duration ttl, int cap) {
        this.jobs = db.getCollection(ImportJobRepository.JOBS);
        this.cap = cap;
        Map<ImportJob.Status, Double> nan = new EnumMap<>(ImportJob.Status.class);
        for (ImportJob.Status s : BACKLOG) nan.put(s, Double.NaN);
        this.cache = new SnapshotCache<>(new Snapshot(nan, Double.NaN), clock, ttl, this::load);
        for (ImportJob.Status s : BACKLOG) {
            Gauge.builder("import_jobs_active", () -> cache.get().perStatus().get(s))
                    .tag("status", s.name().toLowerCase(java.util.Locale.ROOT))
                    .description("non-terminal import jobs in this status, capped at " + cap).register(registry);
        }
        Gauge.builder("import_job_oldest_active_age_seconds", () -> cache.get().oldestAgeSeconds()).baseUnit("seconds")
                .description("seconds since the least recently progressed VALIDATING or APPLYING job last updated; 0 when none")
                .register(registry);
    }

    private Snapshot load(Instant now) {
        CountOptions count = new CountOptions().limit(cap).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS);
        Map<ImportJob.Status, Double> per = new EnumMap<>(ImportJob.Status.class);
        for (ImportJob.Status s : BACKLOG) {
            per.put(s, (double) jobs.countDocuments(Filters.eq("status", s.name()), count));
        }
        Document oldest = jobs.find(Filters.in("status", ImportJob.Status.VALIDATING.name(), ImportJob.Status.APPLYING.name()))
                .sort(Sorts.ascending("updated_at")).projection(Projections.include("updated_at")).limit(1)
                .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).first();
        double age = 0;
        if (oldest != null) {
            Date at = oldest.getDate("updated_at");
            if (at != null) age = Math.max(0, Duration.between(at.toInstant(), now).toMillis() / 1000.0);
        }
        return new Snapshot(per, age);
    }
}
