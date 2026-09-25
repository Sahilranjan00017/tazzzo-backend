package com.tazzzo.commerce.read;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.media.MediaLookup;
import com.tazzzo.media.MediaOwnerType;
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
     * Rebuild one SKU's base card from authoritative sources. Deterministic and replay-safe:
     * unchanged sources converge to {@link RebuildOutcome#NOOP} with no write and no version
     * churn; concurrent rebuilders converge via CAS + bounded retry.
     */
    public RebuildOutcome rebuildOne(String skuId) {
        try {
            Optional<CatalogCardFacts> factsOpt = catalog.findEligibleCard(skuId);
            if (factsOpt.isEmpty()) {
                // Typed ineligible/unknown — derived rows are disposable (STEP 17): delete so an
                // unpublished product can never remain consumer-visible via a stale projection.
                long removed = db.getCollection(COLLECTION)
                        .deleteOne(Filters.eq("sku_id", skuId)).getDeletedCount();
                if (removed > 0) {
                    log.info("projection_rebuild_missing_catalog sku={} action=removed", skuId);
                    return RebuildOutcome.REMOVED;
                }
                log.debug("projection_rebuild_missing_catalog sku={} action=none", skuId);
                return RebuildOutcome.MISSING;
            }
            CatalogCardFacts facts = factsOpt.get();
            PriceLookup price = prices.findCurrentPrice(skuId);
            MediaFacts mediaFacts = resolveMedia(facts);

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                Document existing = db.getCollection(COLLECTION)
                        .find(Filters.eq("sku_id", skuId)).first();
                long nextVersion = existing == null
                        ? 1L
                        : Math.addExact(((Number) existing.get("projection_version")).longValue(), 1);
                ProductCardBaseProjection candidate = derive(facts, price, mediaFacts,
                        existing == null ? 1L : nextVersion);

                if (existing != null && candidate.contentEquals(fromDocument(existing))) {
                    log.debug("projection_rebuild_noop sku={} version={}", skuId,
                            ((Number) existing.get("projection_version")).longValue());
                    return RebuildOutcome.NOOP;
                }
                if (existing == null) {
                    try {
                        db.getCollection(COLLECTION).insertOne(toDocument(candidate, true));
                        log.info("projection_rebuild_success sku={} outcome=created", skuId);
                        return RebuildOutcome.CREATED;
                    } catch (MongoWriteException e) {
                        if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                            log.info("projection_write_conflict sku={} reason=create_race attempt={}",
                                    skuId, attempt);
                            continue; // a concurrent rebuilder inserted — re-read and converge
                        }
                        throw e;
                    }
                }
                UpdateResult r = db.getCollection(COLLECTION).replaceOne(
                        Filters.and(Filters.eq("sku_id", skuId),
                                Filters.eq("projection_version",
                                        ((Number) existing.get("projection_version")).longValue())),
                        toDocument(candidate, false)
                                .append("created_at", existing.get("created_at")));
                if (r.getModifiedCount() == 1) {
                    log.info("projection_rebuild_success sku={} outcome=updated version={}",
                            skuId, candidate.projectionVersion());
                    return RebuildOutcome.UPDATED;
                }
                log.info("projection_write_conflict sku={} reason=stale_cas attempt={}", skuId, attempt);
                // concurrent rebuilder advanced the row — loop: re-read; deterministic sources converge
            }
            throw new ProjectionConflictException("rebuild did not converge for " + skuId
                    + " after " + MAX_ATTEMPTS + " attempts");
        } catch (ProjectionConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            // Infrastructure/read failure: nothing was written this attempt; previous row preserved.
            log.warn("projection_rebuild_failure sku={} reason={}", skuId, e.toString());
            throw e;
        }
    }

    // --- derivation ---------------------------------------------------------------

    private record MediaFacts(String primaryAssetKey, Long mediaVersion) { }

    /** SKU media wins; PRODUCT media is the documented fallback (the composer seam lives here). */
    private MediaFacts resolveMedia(CatalogCardFacts facts) {
        MediaLookup sku = media.findMedia(MediaOwnerType.SKU, facts.skuId());
        MediaLookup chosen = sku.isPresent() ? sku
                : media.findMedia(MediaOwnerType.PRODUCT, facts.productId());
        if (!chosen.isPresent()) {
            return new MediaFacts(null, null); // valid card with no image; placeholder is a UI concern
        }
        return new MediaFacts(
                chosen.mediaSet().primary().map(a -> a.assetKey()).orElse(null),
                chosen.mediaSet().version());
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

    static ProductCardBaseProjection fromDocument(Document d) {
        Document sv = d.get("source_versions", Document.class);
        return new ProductCardBaseProjection(
                d.getString("sku_id"), d.getString("product_id"), d.getString("title"),
                d.getString("brand_code"), d.getString("vertical_id"),
                PriceStatus.valueOf(d.getString("price_status")),
                d.getLong("selling_price_paise"), d.getLong("mrp_paise"), d.getString("currency"),
                d.getString("primary_asset_key"),
                sv == null ? 0L : ((Number) sv.get("catalog_version")).longValue(),
                sv == null ? null : sv.getLong("price_version"),
                sv == null ? null : sv.getLong("media_version"),
                ((Number) d.get("projection_version")).longValue());
    }
}
