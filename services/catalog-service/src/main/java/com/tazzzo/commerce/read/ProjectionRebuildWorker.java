package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.repo.FreshnessObservability;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Background drain of {@link ProjectionRebuildQueue#TYPE} work items (PR-10A). Reuses the
 * repository's established C-6 lease-claim pattern (mirroring MergeService/TaintService) — never a
 * parallel queue framework — and delegates the actual derivation to the ONE production seam
 * {@link ProductCardProjectionService#rebuildOne(String)} (no duplicated projection logic).
 *
 * <p><b>Claim / lease (PR-10A review, HIGH):</b> every claim mints a globally-unique
 * {@code lease_token} (a UUID), never a reusable thread name — correctness across pods/JVMs must
 * not depend on {@code Thread.getName()}, which can repeat. {@code lease_owner} is retained as a
 * human-readable observability tag only. {@code findOneAndUpdate} takes one {@code pending} (or
 * lease-expired) item at a time, leases it for 60s, and increments {@code attempt_count}. Bounded
 * per drain by {@code maxItems}; never an unbounded loop or busy spin.
 *
 * <p><b>Generation-guarded completion (PR-10A review, BLOCKER):</b> the claim records the item's
 * {@code request_generation}. On success the row is cleared ONLY IF it still carries THIS claim's
 * {@code lease_token} AND the SAME {@code request_generation}. A source mutation that re-enqueued
 * during processing bumped the generation (and re-armed {@code status=pending}), so the delete
 * no-ops and the newer request survives to be reprocessed — a mutation after a claim is never lost.
 * A stale worker whose lease expired and was reclaimed by another worker (different token) likewise
 * cannot clear the newer claim.
 *
 * <p><b>Result semantics (PR-10A review):</b> {@link DrainResult} distinguishes {@code rebuilt}
 * (successful re-derivations) from {@code cleared} (items fully drained). A rebuild that succeeded
 * but was SUPERSEDED by a newer generation counts as rebuilt-but-not-cleared — the item stays
 * pending and is reprocessed — so observability never reports it as done.
 *
 * <p><b>Failure / retry:</b> a rebuild that throws is caught per item, logged (safe class only, no
 * throwable/message), and left leased; the lease expires and the item is retried — the same
 * retry-until-success policy as the existing work_queue workers (no dead-letter exists in the
 * repository, so none is invented). {@code attempt_count} is tracked for observability. One poison
 * SKU never aborts the batch, and an outage is never converted into a business state.
 */
public class ProjectionRebuildWorker {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuildWorker.class);
    private static final long LEASE_MS = 60_000L;

    private final MongoDatabase db;
    private final ProductCardProjectionService projectionService;
    private final Clock clock;
    private final String workerId;
    private final FreshnessObservability observability; // nullable: PR-10C, optional by design

    public ProjectionRebuildWorker(MongoDatabase db,
                                   ProductCardProjectionService projectionService,
                                   Clock clock) {
        this(db, projectionService, clock, null);
    }

    /** PR-10C: with observability, every claim/result/completion records a bounded signal. */
    public ProjectionRebuildWorker(MongoDatabase db,
                                   ProductCardProjectionService projectionService,
                                   Clock clock, FreshnessObservability observability) {
        this.db = Objects.requireNonNull(db);
        this.projectionService = Objects.requireNonNull(projectionService);
        this.clock = Objects.requireNonNull(clock);
        this.observability = observability;
        // Observability tag only; correctness rides on the per-claim lease_token.
        this.workerId = "cardw-" + UUID.randomUUID();
    }

    /** Rebuilt = successful re-derivations; cleared = items fully drained (not superseded). */
    public record DrainResult(int rebuilt, int cleared) { }

    /** Drain up to {@code maxItems} rebuild requests. */
    public DrainResult drain(int maxItems) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        int rebuilt = 0;
        int cleared = 0;
        for (int i = 0; i < maxItems; i++) {
            String leaseToken = UUID.randomUUID().toString();
            Document item = claim(leaseToken);
            if (item == null) {
                break; // queue drained
            }
            String skuId = item.getString("sku_id");
            long claimedGeneration = asLong(item.get("request_generation"));
            if (observability != null) {
                observability.rebuildAttempted();
                java.util.Date requestedAt = item.getDate("requested_at");
                if (requestedAt != null) {
                    observability.queueLag(java.time.Duration.between(requestedAt.toInstant(), clock.instant()));
                }
            }
            try {
                RebuildOutcome outcome = projectionService.rebuildOne(skuId);
                rebuilt++;
                if (observability != null) {
                    observability.rebuildResult(toObservedResult(outcome));
                }
                // Clear ONLY if this exact claim still owns the row AND no newer mutation arrived
                // (same generation). Otherwise the newer request stays pending and is reprocessed.
                long deleted = db.getCollection(ProjectionRebuildQueue.COLLECTION).deleteOne(Filters.and(
                        Filters.eq("_id", item.get("_id")),
                        Filters.eq("lease_token", leaseToken),
                        Filters.eq("request_generation", claimedGeneration))).getDeletedCount();
                if (deleted == 1) {
                    cleared++;
                    log.debug("freshness_rebuild_cleared sku={}", skuId);
                    if (observability != null) {
                        observability.rebuildCompletion(FreshnessObservability.Completion.CLEARED);
                    }
                } else {
                    log.debug("freshness_rebuild_superseded sku={} — newer request pending", skuId);
                    if (observability != null) {
                        observability.rebuildCompletion(FreshnessObservability.Completion.SUPERSEDED);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("freshness_rebuild_failure sku={} attempt={} type={}",
                        skuId, asLong(item.get("attempt_count")), e.getClass().getSimpleName());
                if (observability != null) {
                    observability.rebuildFailure();
                }
            }
        }
        return new DrainResult(rebuilt, cleared);
    }

    private Document claim(String leaseToken) {
        Date now = Date.from(clock.instant());
        return db.getCollection(ProjectionRebuildQueue.COLLECTION).findOneAndUpdate(
                Filters.and(Filters.eq("type", ProjectionRebuildQueue.TYPE),
                        Filters.or(Filters.eq("status", "pending"),
                                Filters.and(Filters.eq("status", "leased"),
                                        Filters.lt("lease_until", now)))),
                Updates.combine(Updates.set("status", "leased"),
                        Updates.set("lease_owner", workerId),
                        Updates.set("lease_token", leaseToken),
                        Updates.set("lease_until", new Date(now.getTime() + LEASE_MS)),
                        Updates.inc("attempt_count", 1)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    private static long asLong(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    /**
     * PR-10C final review #3 — the explicit, one-way mapping from this module's own
     * {@link RebuildOutcome} to the neutral {@code catalog.repo} vocabulary. {@code catalog.repo}
     * cannot import {@code commerce.read.RebuildOutcome} (ArchUnit), so the mapping direction stays
     * {@code commerce.read → catalog.repo}, never the reverse.
     */
    private static FreshnessObservability.RebuildResult toObservedResult(RebuildOutcome outcome) {
        return switch (outcome) {
            case CREATED -> FreshnessObservability.RebuildResult.CREATED;
            case UPDATED -> FreshnessObservability.RebuildResult.UPDATED;
            case NOOP -> FreshnessObservability.RebuildResult.NOOP;
            case REMOVED -> FreshnessObservability.RebuildResult.REMOVED;
            case MISSING -> FreshnessObservability.RebuildResult.MISSING;
        };
    }
}
