package com.tazzzo.commerce.read;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.media.MediaReadPort;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceReadPort;
import com.tazzzo.pricing.PriceStatus;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic builder/rebuilder of {@link ProductCardBaseProjection} rows (PR-07).
 *
 * <p><b>FRESHNESS OWNERSHIP (STEP 14 decision — Option B, explicit rebuild):</b> nothing wires
 * this to domain mutations yet, and there is NO background worker and NO request path. THIS
 * PROJECTION IS NOT LIVE: the first consumer PR (runtime enrichment) MUST wire write-through or
 * reconciliation BEFORE any request path serves these rows — placing an unwired projection on a
 * production path is forbidden. Until then, {@link #rebuildOne} is the only freshness mechanism
 * and is safe to replay at any time (deterministic from sources).
 *
 * <p><b>Eventual consistency (STEP 27, stated honestly):</b> one rebuild reads Catalog, Pricing
 * and Media as SEPARATE reads — there is NO cross-domain transaction, so a row may reflect a
 * mixed snapshot. That is acceptable for BROWSE data by design (ADR-005): source versions are
 * captured for reconciliation, rebuilds are replayable, and checkout later revalidates
 * authoritative price/stock (ADR-006). No stronger consistency is claimed.
 *
 * <p><b>Audit (STEP 25 decision):</b> projection writes are DERIVED-cache maintenance, not
 * business events — authoritative history already lives in product_events / price_events /
 * domain_events. Therefore: operational logs only; no domain audit rows; single-document
 * upserts need no multi-doc transaction.
 *
 * <p><b>Failure preservation (STEP 28):</b> a THROWN source read (infrastructure failure) aborts
 * the rebuild before any write — the previous good row is preserved. Only a TYPED "missing /
 * ineligible" answer from the Catalog port removes a row.
 *
 * <p><b>Hooks:</b> projection_rebuild_success, projection_rebuild_noop,
 * projection_rebuild_missing_catalog, projection_rebuild_failure, projection_write_conflict.
 */
public class ProductCardProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProductCardProjectionService.class);
    static final String COLLECTION = "product_card_base";
    private static final int MAX_ATTEMPTS = 3;

    private final CatalogCardReadPort catalog;
    private final PriceReadPort prices;
    private final MediaReadPort media;
    private final MongoDatabase db;
    private final Clock clock;

    public ProductCardProjectionService(CatalogCardReadPort catalog, PriceReadPort prices,
                                        MediaReadPort media, MongoDatabase db, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog);
        this.prices = Objects.requireNonNull(prices);
        this.media = Objects.requireNonNull(media);
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Rebuild one SKU's base card from authoritative sources.
     *
     * <p><b>UNIFIED RECONCILIATION LOOP (PR-07 review, STEP 2/3/4 fix):</b> EVERY attempt is a
     * COMPLETE, FRESH authoritative observation — projection row, Catalog, Pricing, Media are
     * all re-read on each iteration, and NOTHING is carried across attempts. The projection-row
     * CAS alone cannot protect source freshness: a rebuilder that loses a CAS race while holding
     * an earlier source snapshot would otherwise REGRESS a newer projection with older data, and
     * a stale "catalog ineligible" observation could otherwise blind-delete a freshly rebuilt
     * row. Therefore: the update path CASes on the observed {@code projection_version}, the
     * DELETE path equally CASes on the observed version, and ANY conflict (stale CAS, create
     * race, delete race) restarts the attempt from a new observation of every source.
     * Deterministic sources make retries converge (typically to NOOP).
     *
     * <p><b>Source-read assumption (STEP 8, documented):</b> the Catalog/Pricing/Media ports
     * return CURRENT COMMITTED state — with full re-reads per attempt that is sufficient; if a
     * port ever intentionally serves historical/non-monotonic reads, projection reconciliation
     * will additionally need source-generation fencing (not required today).
     */
    public RebuildOutcome rebuildOne(String skuId) {
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                // 1) FRESH projection-row observation FIRST: every conditional mutation in this
                //    attempt (update OR delete) CASes on exactly this observed version.
                Document existing = db.getCollection(COLLECTION)
                        .find(Filters.eq("sku_id", skuId)).first();
                Long observedVersion = existing == null ? null
                        : ((Number) existing.get("projection_version")).longValue();

                // 2) FRESH catalog observation.
                Optional<CatalogCardFacts> factsOpt = catalog.findEligibleCard(skuId);
                if (factsOpt.isEmpty()) {
                    if (existing == null) {
                        log.debug("projection_rebuild_missing_catalog sku={} action=none", skuId);
                        return RebuildOutcome.MISSING;
                    }
                    // Version-guarded removal: a stale ineligible observation must never delete
                    // a row a concurrent rebuilder has just refreshed (STEP 3).
                    long removed = db.getCollection(COLLECTION)
                            .deleteOne(Filters.and(Filters.eq("sku_id", skuId),
                                    Filters.eq("projection_version", observedVersion)))
                            .getDeletedCount();
                    if (removed == 1) {
                        log.info("projection_rebuild_missing_catalog sku={} action=removed", skuId);
                        return RebuildOutcome.REMOVED;
                    }
                    log.info("projection_write_conflict sku={} reason=delete_race attempt={}",
                            skuId, attempt);
                    continue; // re-observe EVERYTHING
                }
                CatalogCardFacts facts = factsOpt.get();

                // 3) FRESH pricing + media observations.
                PriceLookup price = prices.findCurrentPrice(skuId);
                MediaFacts mediaFacts = resolveMedia(facts);

                long nextVersion;
                try {
                    nextVersion = existing == null ? 1L : Math.addExact(observedVersion, 1);
                } catch (ArithmeticException e) {
                    throw new ProjectionConflictException(
                            "projection_version overflow for " + skuId + "; row preserved");
                }
                ProductCardBaseProjection candidate = derive(facts, price, mediaFacts, nextVersion);

                if (existing != null && candidate.contentEquals(fromDocument(existing))) {
                    log.debug("projection_rebuild_noop sku={} version={}", skuId, observedVersion);
                    return RebuildOutcome.NOOP;
                }
                if (existing == null) {
                    try {
                        db.getCollection(COLLECTION).insertOne(toDocument(candidate, true));
                        log.info("projection_rebuild_success sku={} outcome=created", skuId);
                        return RebuildOutcome.CREATED;
                    } catch (com.mongodb.MongoException e) {
                        if (isDuplicateKey(e)) {
                            log.info("projection_write_conflict sku={} reason=create_race attempt={}",
                                    skuId, attempt);
                            continue; // re-observe EVERYTHING and converge
                        }
                        throw e;
                    }
                }
                UpdateResult r = db.getCollection(COLLECTION).replaceOne(
                        Filters.and(Filters.eq("sku_id", skuId),
                                Filters.eq("projection_version", observedVersion)),
                        toDocument(candidate, false)
                                .append("created_at", existing.get("created_at")));
                if (r.getModifiedCount() == 1) {
                    log.info("projection_rebuild_success sku={} outcome=updated version={}",
                            skuId, candidate.projectionVersion());
                    return RebuildOutcome.UPDATED;
                }
                log.info("projection_write_conflict sku={} reason=stale_cas attempt={}", skuId, attempt);
                // loop: next attempt re-reads every source AND the row
            }
            throw new ProjectionConflictException("rebuild did not converge for " + skuId
                    + " after " + MAX_ATTEMPTS + " attempts");
        } catch (ProjectionConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            // Infrastructure/read failure (any source): nothing was written this attempt —
            // the previous good row is preserved (STEP 28).
            log.warn("projection_rebuild_failure sku={} reason={}", skuId, e.toString());
            throw e;
        }
    }

    /** Duplicate-key detection across the exception shapes Mongo can produce (PR-05/06 lesson). */
    private static boolean isDuplicateKey(com.mongodb.MongoException e) {
        if (e instanceof MongoWriteException w) {
            return w.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY;
        }
        if (com.mongodb.ErrorCategory.fromErrorCode(e.getCode()) == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("E11000");
    }

    // --- derivation ---------------------------------------------------------------

    private record MediaFacts(String primaryAssetKey, Long mediaVersion) { }

    /**
     * FROZEN FALLBACK POLICY (PR-07 review, STEP 9), now owned by {@link MediaSelection} so the
     * PDP composer (PR-09) applies the IDENTICAL branch rules — SKU MISSING → PRODUCT fallback;
     * SKU INACTIVE → explicit suppression, no fallback; neither present → no image (valid card,
     * placeholder is a UI concern).
     */
    private MediaFacts resolveMedia(CatalogCardFacts facts) {
        return MediaSelection.selectFor(media, facts.skuId(), facts.productId())
                .map(set -> new MediaFacts(
                        set.primary().map(a -> a.assetKey()).orElse(null), set.version()))
                .orElseGet(() -> new MediaFacts(null, null));
    }

    private ProductCardBaseProjection derive(CatalogCardFacts facts, PriceLookup price,
                                             MediaFacts mediaFacts, long projectionVersion) {
        boolean active = price.status() == PriceStatus.ACTIVE && price.price() != null;
        return new ProductCardBaseProjection(
                facts.skuId(), facts.productId(), facts.title(), facts.brandCode(),
                facts.verticalId(),
                price.status(),
                active ? price.price().sellingPricePaise() : null,
                active ? price.price().mrpPaise() : null,
                active ? price.price().currency().name() : null,
                mediaFacts.primaryAssetKey(),
                facts.catalogVersion(),
                price.price() == null ? null : price.price().version(),
                mediaFacts.mediaVersion(),
                projectionVersion);
    }

    // --- persistence mapping --------------------------------------------------------

    private Document toDocument(ProductCardBaseProjection p, boolean create) {
        Date now = Date.from(clock.instant());
        Document d = new Document("sku_id", p.skuId())
                .append("product_id", p.productId())
                .append("title", p.title())
                .append("brand_code", p.brandCode())
                .append("vertical_id", p.verticalId())
                .append("price_status", p.priceStatus().name())
                .append("selling_price_paise", p.sellingPricePaise())
                .append("mrp_paise", p.mrpPaise())
                .append("currency", p.currency())
                .append("primary_asset_key", p.primaryAssetKey())
                .append("source_versions", new Document("catalog_version", p.catalogVersion())
                        .append("price_version", p.priceVersion())
                        .append("media_version", p.mediaVersion()))
                .append("projection_version", p.projectionVersion())
                .append("updated_at", now);
        if (create) {
            d.append("created_at", now);
        }
        return d;
    }

    /**
     * FAIL-FAST reconstruction (PR-07 review, STEP 14): a derived collection is rebuildable, so
     * corrupt persisted state must surface loudly — never be defaulted into valid-looking data.
     * Missing {@code source_versions}/{@code catalog_version}/{@code projection_version}/
     * {@code price_status} (or an unknown status/currency) throws; nothing silently becomes 0.
     */
    static ProductCardBaseProjection fromDocument(Document d) {
        Document sv = d.get("source_versions", Document.class);
        if (sv == null || sv.get("catalog_version") == null
                || d.get("projection_version") == null || d.getString("price_status") == null) {
            throw new IllegalStateException("corrupt product_card_base row for sku_id="
                    + d.getString("sku_id") + " — rebuild required");
        }
        return new ProductCardBaseProjection(
                d.getString("sku_id"), d.getString("product_id"), d.getString("title"),
                d.getString("brand_code"), d.getString("vertical_id"),
                PriceStatus.valueOf(d.getString("price_status")),
                d.getLong("selling_price_paise"), d.getLong("mrp_paise"), d.getString("currency"),
                d.getString("primary_asset_key"),
                ((Number) sv.get("catalog_version")).longValue(),
                sv.getLong("price_version"),
                sv.getLong("media_version"),
                ((Number) d.get("projection_version")).longValue());
    }
}
