package com.tazzzo.commerce.read;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.tazzzo.catalog.consumer.ConsumerEligibility;
import com.tazzzo.catalog.consumer.ConsumerProductResponse;
import com.tazzzo.catalog.consumer.ConsumerProjectionService;
import org.bson.Document;

import java.util.List;
import java.util.Objects;

/**
 * Default {@link CatalogProductDetailReadPort}: ONE point read of the product document by
 * primary key (narrow projection — provenance/evidence/identity internals never enter memory),
 * gated through {@link ConsumerEligibility#isEligible(Document)} and projected through
 * {@link ConsumerProjectionService} — BOTH reused, never re-implemented (PR-07 precedent).
 *
 * <p><b>Eligibility outcomes:</b> absent document → NOT_FOUND; present but ineligible →
 * INELIGIBLE. A malformed projection policy throws {@code ConsumerProjectionPolicyException} —
 * an operations/configuration failure that PROPAGATES; it is never disguised as a missing
 * product (mirrors the ratified PDP-1 step 7 → 503 rule).
 *
 * <p><b>Merge chains (documented seam, NOT implemented here):</b> a merged loser has
 * {@code lifecycle=merged} and therefore lands in INELIGIBLE. The ratified consumer PDP-1
 * resolves merge chains to the survivor (PDP-MERGE-1, 32-hop bound); whether the COMMERCE PDP
 * route does the same is a PUBLIC-surface decision that belongs to PR-10 — at launch no
 * commerce deep links exist and browse never lists a merged loser (its projection row is
 * reconciled away), so nothing can navigate here.
 *
 * <p><b>Release scope (REL-MEM-1, PR-07 precedent):</b> release scopes taxonomy, not
 * per-product eligibility — so no release parameter belongs on this port. The frozen public
 * route's {@code release} parameter and the release-scoped vertical-reachability gate
 * (PDP-1 step 6) are PR-10 envelope obligations, recorded there.
 *
 * <p>At launch {@code skuId == productId}, so the lookup key is the product {@code _id};
 * both ids are carried separately so ONLY this reader changes when variants introduce SKU
 * documents.
 */
public class CatalogProductDetailReader implements CatalogProductDetailReadPort {

    /** Exactly what eligibility + projection + the facts need; nothing internal. */
    static final List<String> DETAIL_FIELDS = List.of(
            "_id", "title", "brand_code", "product_type", "lifecycle",
            "classification.status", "classification.vertical_id", "attributes", "version");

    private final MongoDatabase db;
    private final ConsumerProjectionService projection;

    public CatalogProductDetailReader(MongoDatabase db, ConsumerProjectionService projection) {
        this.db = Objects.requireNonNull(db);
        this.projection = Objects.requireNonNull(projection);
    }

    @Override
    public CatalogDetailLookup findDetail(String skuId) {
        if (skuId == null || skuId.isBlank()) {
            return CatalogDetailLookup.notFound();
        }
        Document p = db.getCollection("products")
                .find(Filters.eq("_id", skuId))
                .projection(Projections.include(DETAIL_FIELDS))
                .first();
        if (p == null) {
            return CatalogDetailLookup.notFound();
        }
        if (!ConsumerEligibility.isEligible(p)) {
            return CatalogDetailLookup.ineligible();
        }
        // Policy-governed attribute projection (RP-1..RP-6d) — the one attribute surface.
        ConsumerProductResponse projected = projection.project(p);
        Document classification = p.get("classification", Document.class);
        return CatalogDetailLookup.found(new CatalogProductDetailFacts(
                skuId,
                skuId, // launch: product _id IS the SKU id; carried separately by design
                p.getString("title"),
                p.getString("brand_code"),
                classification == null ? null : classification.getString("vertical_id"),
                p.get("version") == null ? 0L : ((Number) p.get("version")).longValue(),
                projected.attributes()));
    }
}
