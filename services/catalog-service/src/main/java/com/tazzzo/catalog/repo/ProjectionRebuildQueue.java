package com.tazzzo.catalog.repo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.conversions.Bson;

import java.time.Clock;
import java.util.Date;
import java.util.Objects;

/**
 * Neutral producer that records a "this SKU's product-card projection needs reconciliation"
 * request on the existing {@code work_queue} (PR-10A). It is the smallest freshness seam: the
 * authoritative domains (Catalog via {@link WritePath}, Pricing, Media) call it INSIDE their own
 * write transaction so the rebuild request is durable with the source mutation — no dual-write
 * corruption — and a background worker in {@code commerce.read} drains it.
 *
 * <p><b>Why here, and why not {@code auxWrite}:</b> every domain already depends on
 * {@code catalog.repo}; this package imports NOTHING from {@code commerce.*}, so a domain calling
 * it introduces no forbidden edge (ArchUnit {@code domains_do_not_depend_on_commerce_read} holds).
 * It writes {@code work_queue} DIRECTLY on the session rather than through
 * {@link WritePath#auxWrite} because auxWrite always appends a {@code product_events} audit row —
 * a price or media change is not a product event, and the enqueue is bookkeeping, not audit.
 *
 * <p><b>One row per SKU, but every mutation WAKES it (PR-10A review, BLOCKER):</b> the item
 * {@code _id} is deterministic ({@code "card_rebuild:" + skuId}) so there is never more than one
 * row per SKU, but each enqueue does more than {@code $setOnInsert} — it {@code $set status=pending}
 * (re-arming a row a worker is currently holding as {@code leased}) and monotonically
 * {@code $inc request_generation}. That generation is the wake token: a mutation occurring AFTER a
 * worker claimed the row bumps the generation, so the worker's completion (which is CAS-guarded on
 * the generation it claimed) can no longer clear the row — guaranteeing at least one further
 * rebuild after that mutation. Duplicate enqueues still collapse to one row; they simply advance
 * the generation, which is harmless (the worker re-derives idempotently).
 *
 * <p><b>Global only:</b> the payload carries the SKU id and a coarse reason and nothing else — no
 * PIN, no serviceAreaId, no fulfillmentLocationId, no stock/availability. The projection it drives
 * is global and location-agnostic (PR-07 boundary).
 */
public class ProjectionRebuildQueue {

    /** work_queue item {@code type} for product-card projection rebuilds. */
    public static final String TYPE = "product_card_rebuild";
    /** The shared work_queue collection this producer and the commerce.read worker operate on. */
    public static final String COLLECTION = "work_queue";
    private static final String ID_PREFIX = "card_rebuild:";

    private final MongoDatabase db;
    private final Clock clock;

    public ProjectionRebuildQueue(MongoDatabase db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Enqueue a rebuild request for {@code skuId} on the caller's transaction session — durable
     * with the source mutation. Wakes an existing row (status→pending) and advances the generation.
     */
    public void requestRebuild(ClientSession session, String skuId, String reason) {
        Objects.requireNonNull(session, "session required");
        db.getCollection(COLLECTION).updateOne(session, Filters.eq("_id", ID_PREFIX + require(skuId)),
                wake(skuId, reason), new UpdateOptions().upsert(true));
    }

    /**
     * Enqueue outside a source transaction (reconciliation/backfill). Same wake+generation upsert.
     */
    public void requestRebuild(String skuId, String reason) {
        db.getCollection(COLLECTION).updateOne(Filters.eq("_id", ID_PREFIX + require(skuId)),
                wake(skuId, reason), new UpdateOptions().upsert(true));
    }

    /**
     * Insert-if-absent immutable fields; on EVERY enqueue re-arm the item ({@code status=pending})
     * and monotonically bump {@code request_generation} (the wake token) so a mutation during a
     * worker's lease is never lost. {@code request_generation} lives ONLY in {@code $inc} (never in
     * {@code $setOnInsert}) — on insert it becomes 1, on an existing row it increments.
     */
    private Bson wake(String skuId, String reason) {
        return Updates.combine(
                Updates.setOnInsert("type", TYPE),
                Updates.setOnInsert("sku_id", skuId),
                Updates.setOnInsert("created_at", Date.from(clock.instant())),
                Updates.setOnInsert("attempt_count", 0),
                Updates.set("status", "pending"),
                Updates.set("reason", reason == null ? "unspecified" : reason),
                Updates.set("requested_at", Date.from(clock.instant())),
                Updates.inc("request_generation", 1L));
    }

    private static String require(String skuId) {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId required for rebuild request");
        }
        return skuId;
    }
}
