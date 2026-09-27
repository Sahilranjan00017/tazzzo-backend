package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.tazzzo.catalog.consumer.ConsumerEligibility;
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
 * backstop behind the source hooks. It repairs the cases hooks cannot: an empty collection at
 * deployment, data predating the hooks, a dropped/failed work item, or drift. It NEVER rebuilds
 * synchronously and NEVER fans out thousands in one tick — it enqueues bounded batches of
 * {@link ProjectionRebuildQueue} requests and lets the worker do the derivation.
 *
 * <p><b>Keyset-paged, bounded:</b> each pass scans at most {@code limit} rows ordered by id from an
 * in-memory checkpoint that advances and wraps at the end — no full-collection scan per tick, no
 * one-transaction mega-rebuild. Enqueues are idempotent, so re-scanning after a wrap or restart is
 * harmless (a still-pending request is not duplicated).
 *
 * <p><b>Two passes:</b> {@code reconcileMissing} finds consumer-eligible products with no
 * projection row and enqueues a build; {@code reconcileOrphans} finds projection rows whose product
 * is no longer eligible and enqueues a rebuild (the worker's {@code rebuildOne} version-CAS-deletes
 * it). Both reuse the ONE ratified {@link ConsumerEligibility} predicate — no second eligibility.
 */
public class ProjectionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ProjectionReconciler.class);
    private static final String PROJECTION = ProductCardProjectionService.COLLECTION;

    private final MongoDatabase db;
    private final ProjectionRebuildQueue queue;

    private String missingCheckpoint; // null = scan from start
    private String orphanCheckpoint;

    public ProjectionReconciler(MongoDatabase db, ProjectionRebuildQueue queue) {
        this.db = Objects.requireNonNull(db);
        this.queue = Objects.requireNonNull(queue);
    }

    /** Enqueue rebuilds for eligible products missing a projection row. Returns count enqueued. */
    public int reconcileMissing(int limit) {
        requirePositive(limit);
        List<String> productIds = eligiblePage(missingCheckpoint, limit);
        if (productIds.isEmpty()) {
            missingCheckpoint = null; // reached the end; wrap next pass
            return 0;
        }
        missingCheckpoint = productIds.get(productIds.size() - 1);
        Set<String> covered = existingProjectionSkus(productIds);
        int enqueued = 0;
        for (String productId : productIds) {
            if (!covered.contains(productId)) { // launch: product _id == sku id
                queue.requestRebuild(productId, "reconcile_missing");
                enqueued++;
            }
        }
        if (enqueued > 0) {
            log.info("freshness_reconcile_missing enqueued={} scanned={}", enqueued, productIds.size());
        }
        return enqueued;
    }

    /** Enqueue rebuilds for projection rows whose product is no longer eligible (worker removes). */
    public int reconcileOrphans(int limit) {
        requirePositive(limit);
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

    private Set<String> existingProjectionSkus(List<String> skuIds) {
        Set<String> out = new HashSet<>();
        for (Document d : db.getCollection(PROJECTION).find(Filters.in("sku_id", skuIds))
                .projection(Projections.include("sku_id"))) {
            out.add(d.getString("sku_id"));
        }
        return out;
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
