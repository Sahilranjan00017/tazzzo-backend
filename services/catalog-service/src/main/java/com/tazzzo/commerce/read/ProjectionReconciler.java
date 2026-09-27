package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.tazzzo.catalog.consumer.ConsumerEligibility;
import com.tazzzo.catalog.repo.FreshnessObservability;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded reconciliation / backfill for {@code product_card_base} (PR-10A) — the correctness
 * backstop behind the source hooks. It NEVER rebuilds synchronously and NEVER fans out thousands
 * in one tick — it enqueues bounded batches of {@link ProjectionRebuildQueue} requests and lets the
 * worker do the derivation.
 *
 * <p><b>Drift reconciliation (PR-10A review, HIGH — Option A rolling rebuild):</b>
 * {@link #reconcileDrift} keyset-pages consumer-eligible products and enqueues EVERY scanned SKU
 * for re-derivation. This repairs the cases the hooks cannot: an empty collection at deployment,
 * data predating the hooks, a dropped/failed queue request, and eventual content/source-version
 * drift on an EXISTING row (the case a missing-only pass would miss). The worker's
 * {@code rebuildOne} is idempotent — a fresh row rebuilds as a NOOP, a stale one is corrected — so
 * re-enqueuing everything is safe and needs no per-SKU source-version read here.
 *
 * <p><b>Orphan reconciliation:</b> {@link #reconcileOrphans} keyset-pages projection rows whose
 * product is no longer eligible and enqueues a rebuild (the worker's version-CAS delete removes it).
 *
 * <p><b>Bounded + rolling:</b> each pass scans at most {@code limit} rows ordered by id from an
 * in-memory checkpoint that advances and wraps at the end — no full-collection scan per tick, no
 * one-transaction mega-rebuild, eventual full coverage. Enqueues are idempotent (they advance the
 * request generation), so re-scanning after a wrap or a restart is harmless. Both passes reuse the
 * ONE ratified {@link ConsumerEligibility} predicate — no second eligibility.
 */
public class ProjectionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ProjectionReconciler.class);
    private static final String PROJECTION = ProductCardProjectionService.COLLECTION;

    private final MongoDatabase db;
    private final ProjectionRebuildQueue queue;
    private final FreshnessObservability observability; // nullable: PR-10C, optional by design

    private String driftCheckpoint;  // null = scan from start
    private String orphanCheckpoint;

    public ProjectionReconciler(MongoDatabase db, ProjectionRebuildQueue queue) {
        this(db, queue, null);
    }

    /** PR-10C: with observability, every pass records enqueued count + a pass-ran signal. */
    public ProjectionReconciler(MongoDatabase db, ProjectionRebuildQueue queue,
                                FreshnessObservability observability) {
        this.db = Objects.requireNonNull(db);
        this.queue = Objects.requireNonNull(queue);
        this.observability = observability;
    }

    /**
     * Enqueue a rebuild for every consumer-eligible product in the next bounded page — repairs
     * missing rows, dropped requests and stale/drifted existing rows alike. Returns count enqueued.
     */
    public int reconcileDrift(int limit) {
        requirePositive(limit);
        if (observability != null) {
            observability.reconcilePass(FreshnessObservability.ReconcilePass.DRIFT);
        }
        List<String> productIds = eligiblePage(driftCheckpoint, limit);
        if (productIds.isEmpty()) {
            driftCheckpoint = null; // reached the end; wrap next pass for eventual full coverage
            return 0;
        }
        driftCheckpoint = productIds.get(productIds.size() - 1);
        for (String productId : productIds) { // launch: product _id == sku id
            queue.requestRebuild(productId, "reconcile_drift");
        }
        log.info("freshness_reconcile_drift enqueued={}", productIds.size());
        if (observability != null) {
            observability.reconcileEnqueued(FreshnessObservability.ReconcilePass.DRIFT, productIds.size());
        }
        return productIds.size();
    }

    /** Enqueue rebuilds for projection rows whose product is no longer eligible (worker removes). */
    public int reconcileOrphans(int limit) {
        requirePositive(limit);
        if (observability != null) {
            observability.reconcilePass(FreshnessObservability.ReconcilePass.ORPHAN);
        }
        List<String> skus = projectionPage(orphanCheckpoint, limit);
        if (skus.isEmpty()) {
            orphanCheckpoint = null;
            return 0;
        }
        orphanCheckpoint = skus.get(skus.size() - 1);
        Set<String> eligible = eligibleSubset(skus);
        int enqueued = 0;
        for (String sku : skus) {
            if (!eligible.contains(sku)) {
                queue.requestRebuild(sku, "reconcile_orphan");
                enqueued++;
            }
        }
        if (enqueued > 0) {
            log.info("freshness_reconcile_orphan enqueued={} scanned={}", enqueued, skus.size());
        }
        if (observability != null) {
            observability.reconcileEnqueued(FreshnessObservability.ReconcilePass.ORPHAN, enqueued);
        }
        return enqueued;
    }

    private List<String> eligiblePage(String afterId, int limit) {
        List<String> ids = new ArrayList<>();
        for (Document d : db.getCollection("products").find(
                        afterId == null ? ConsumerEligibility.filter()
                                : Filters.and(ConsumerEligibility.filter(), Filters.gt("_id", afterId)))
                .projection(Projections.include("_id")).sort(Sorts.ascending("_id")).limit(limit)) {
            ids.add(d.getString("_id"));
        }
        return ids;
    }

    private List<String> projectionPage(String afterId, int limit) {
        List<String> skus = new ArrayList<>();
        for (Document d : db.getCollection(PROJECTION).find(
                        afterId == null ? Filters.empty() : Filters.gt("sku_id", afterId))
                .projection(Projections.include("sku_id")).sort(Sorts.ascending("sku_id")).limit(limit)) {
            skus.add(d.getString("sku_id"));
        }
        return skus;
    }

    private Set<String> eligibleSubset(List<String> productIds) {
        Set<String> out = new HashSet<>();
        for (Document d : db.getCollection("products").find(
                        Filters.and(Filters.in("_id", productIds), ConsumerEligibility.filter()))
                .projection(Projections.include("_id"))) {
            out.add(d.getString("_id"));
        }
        return out;
    }

    private static void requirePositive(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
