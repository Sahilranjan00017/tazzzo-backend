package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Date;
import java.util.Objects;

/**
 * Background drain of {@link ProjectionRebuildQueue#TYPE} work items (PR-10A). Reuses the
 * repository's established C-6 lease-claim pattern (mirroring MergeService/TaintService) — never a
 * parallel queue framework — and delegates the actual derivation to the ONE production seam
 * {@link ProductCardProjectionService#rebuildOne(String)} (no duplicated projection logic).
 *
 * <p><b>Claim / lease:</b> {@code findOneAndUpdate} takes one {@code pending} (or lease-expired)
 * item at a time, leases it for 60s to this worker. Bounded per drain by {@code maxItems}; never
 * an unbounded loop, never a busy spin (returns when the queue is drained).
 *
 * <p><b>Idempotent + race-safe completion:</b> {@code rebuildOne} is deterministic and safe to
 * replay. On success the item is deleted ONLY IF it is still {@code leased} by THIS worker — so a
 * fresh source mutation that re-enqueued during processing (the enqueue sets {@code status=pending}
 * again) is NOT lost: the delete no-ops and the item is reclaimed and reprocessed against the
 * newer sources. Two workers on the same SKU are safe because {@code rebuildOne}'s projection CAS
 * makes concurrent rebuilds converge (PR-07).
 *
 * <p><b>Failure isolation:</b> a rebuild that throws is caught per item, logged, and left leased —
 * the lease expires and the item is retried later. One poison SKU never aborts the batch, and an
 * infrastructure outage is never turned into a business state (the item simply stays pending).
 */
public class ProjectionRebuildWorker {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuildWorker.class);
    private static final long LEASE_MS = 60_000L;

    private final MongoDatabase db;
    private final ProductCardProjectionService projectionService;
    private final Clock clock;

    public ProjectionRebuildWorker(MongoDatabase db,
                                   ProductCardProjectionService projectionService,
                                   Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.projectionService = Objects.requireNonNull(projectionService);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Drain up to {@code maxItems} rebuild requests. Returns the number processed (success). */
    public int drain(int maxItems) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        String owner = Thread.currentThread().getName();
        int processed = 0;
        for (int i = 0; i < maxItems; i++) {
            Document item = claim(owner);
            if (item == null) {
                break; // queue drained
            }
            String skuId = item.getString("sku_id");
            try {
                projectionService.rebuildOne(skuId);
                // Delete ONLY if still leased by us — a re-enqueue (status->pending) or a re-lease
                // by another worker during processing makes this a no-op, so the newer request is
                // reprocessed rather than dropped.
                db.getCollection(ProjectionRebuildQueue.COLLECTION).deleteOne(Filters.and(
                        Filters.eq("_id", item.get("_id")),
                        Filters.eq("status", "leased"),
                        Filters.eq("lease_owner", owner)));
                processed++;
                log.debug("freshness_rebuild_success sku={}", skuId);
            } catch (RuntimeException e) {
                // Left leased; lease expiry makes it reclaimable. Safe class only, no throwable.
                log.warn("freshness_rebuild_failure sku={} type={}", skuId, e.getClass().getSimpleName());
            }
        }
        return processed;
    }

    private Document claim(String owner) {
        Date now = Date.from(clock.instant());
        return db.getCollection(ProjectionRebuildQueue.COLLECTION).findOneAndUpdate(
                Filters.and(Filters.eq("type", ProjectionRebuildQueue.TYPE),
                        Filters.or(Filters.eq("status", "pending"),
                                Filters.and(Filters.eq("status", "leased"),
                                        Filters.lt("lease_until", now)))),
                Updates.combine(Updates.set("status", "leased"),
                        Updates.set("lease_owner", owner),
                        Updates.set("lease_until", new Date(now.getTime() + LEASE_MS))),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }
}
