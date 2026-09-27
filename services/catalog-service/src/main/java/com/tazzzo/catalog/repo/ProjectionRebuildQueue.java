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
 * <p><b>Idempotent by construction:</b> the item {@code _id} is deterministic
 * ({@code "card_rebuild:" + skuId}) and the upsert uses {@code $setOnInsert}, so any number of
 * enqueues for the same SKU while a request is still pending collapse to one row — duplicate
 * source writes never create duplicate work.
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
     * with the source mutation. Idempotent: a pending request for the same SKU is not duplicated.
     */
    public void requestRebuild(ClientSession session, String skuId, String reason) {
        Objects.requireNonNull(session, "session required");
        db.getCollection(COLLECTION).updateOne(session, Filters.eq("_id", ID_PREFIX + require(skuId)),
                onInsert(skuId, reason), new UpdateOptions().upsert(true));
    }

    /**
     * Enqueue outside a source transaction (reconciliation/backfill). Same idempotent upsert; a
     * pending request already parked by a source hook is left untouched.
     */
    public void requestRebuild(String skuId, String reason) {
        db.getCollection(COLLECTION).updateOne(Filters.eq("_id", ID_PREFIX + require(skuId)),
                onInsert(skuId, reason), new UpdateOptions().upsert(true));
    }

    private Bson onInsert(String skuId, String reason) {
        return Updates.combine(
                Updates.setOnInsert("type", TYPE),
                Updates.setOnInsert("sku_id", skuId),
                Updates.setOnInsert("reason", reason == null ? "unspecified" : reason),
                Updates.setOnInsert("status", "pending"),
                Updates.setOnInsert("created_at", Date.from(clock.instant())));
    }

    private static String require(String skuId) {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId required for rebuild request");
        }
        return skuId;
    }
}
