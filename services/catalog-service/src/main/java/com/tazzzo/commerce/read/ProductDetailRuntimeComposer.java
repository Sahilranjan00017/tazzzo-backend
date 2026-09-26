package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaReadPort;
import com.tazzzo.media.MediaSet;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.pricing.PriceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The PDP runtime composer (PR-09): one SKU request → one {@link RuntimeProductDetailLookup}.
 * INTERNAL ONLY — no controller, no request path, no cache, no persistence (composition is
 * ONE-WAY and writes nothing anywhere). The frozen public route {@code /v1/products/{id}} goes
 * live in PR-10, which also owns admission, the release envelope, the release-scoped
 * vertical-reachability gate (PDP-1 step 6) and the explicit projection-freshness mechanism.
 *
 * <p><b>Order of operations (bounded reads, STEP 21):</b>
 * <pre>
 *   1. Catalog detail read (eligibility-gated, attributes policy-projected)   NOT_FOUND /
 *      INELIGIBLE return HERE — zero commerce/media work after rejection
 *   2. product_card_base row read → PR-08 enrichOne (ONE serviceability resolution when a
 *      location is present; ONE inventory read only when SERVICEABLE — rules NOT forked)
 *   3. media set selection (frozen SKU→PRODUCT fallback shared with the projection builder
 *      via MediaSelection) → gallery URL resolution
 * </pre>
 * Typical serviceable-PIN request: 1 product read (+2 bounded attribute-policy reads when the
 * vertical has a policy) + 1 base-row read + 1 serviceability + 1 inventory + ≤2 media reads.
 * Pricing is NEVER re-read here — the base row is the ratified ACTIVE-price snapshot and the
 * PR-08 card is the single discount/buyable authority.
 *
 * <p><b>Projection freshness HARD GATE (STEP 19):</b> {@code product_card_base} stays NOT LIVE
 * and is NOT the existence authority — existence and eligibility come from the Catalog read
 * above. A MISSING base row for an eligible product is a DATA-QUALITY event
 * ({@code commerce_detail_base_missing}), never NOT_FOUND: composition proceeds through the
 * SAME enrichment path with an in-memory degraded base (PriceStatus.MISSING, no amounts, no
 * media key) — fail-closed (never buyable), nothing fabricated, and deliberately NO
 * {@code rebuildOne()} fan-out per request. The embedded card's thumbnail comes from the base
 * SNAPSHOT while the gallery is read fresh from Media; under a stale row they can briefly
 * disagree — that is the documented NOT-LIVE debt PR-10's freshness mechanism closes.
 *
 * <p><b>Failure policy:</b> business absence → status; infrastructure failure → typed exception,
 * propagated untouched, logged here as the SAFE class name only (PR-08 logging discipline: no
 * PIN, no fulfillment location, no exception-message interpolation at WARN).
 */
public class ProductDetailRuntimeComposer {

    private static final Logger log = LoggerFactory.getLogger(ProductDetailRuntimeComposer.class);

    private final CatalogProductDetailReadPort catalogDetail;
    private final ProductCardBaseReadPort baseRead;
    private final ProductCardRuntimeEnricher enricher;
    private final MediaReadPort media;
    private final MediaUrlResolver mediaUrls;

    public ProductDetailRuntimeComposer(CatalogProductDetailReadPort catalogDetail,
                                        ProductCardBaseReadPort baseRead,
                                        ProductCardRuntimeEnricher enricher,
                                        MediaReadPort media,
                                        MediaUrlResolver mediaUrls) {
        this.catalogDetail = Objects.requireNonNull(catalogDetail);
        this.baseRead = Objects.requireNonNull(baseRead);
        this.enricher = Objects.requireNonNull(enricher);
        this.media = Objects.requireNonNull(media);
        this.mediaUrls = Objects.requireNonNull(mediaUrls);
    }

    public RuntimeProductDetailLookup composeDetail(String skuId, LocationQuery location) {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId required");
        }
        Objects.requireNonNull(location, "location required");
        // No try/catch here BY DESIGN: infrastructure exceptions from any port propagate typed
        // and UNTOUCHED (structurally un-wrappable), and each layer logs its OWN stage failure
        // once — the enricher logs commerce_enrich_failure for serviceability/inventory outages,
        // and request-level error logging belongs to the centralized boundary (PR-10). Wrapping
        // here would both double-log and risk disguising an outage as a business state.
        CatalogDetailLookup catalogLookup = catalogDetail.findDetail(skuId);
        switch (catalogLookup.status()) {
            case NOT_FOUND -> {
                log.debug("commerce_detail_not_found sku={}", skuId);
                return RuntimeProductDetailLookup.notFound();
            }
            case INELIGIBLE -> {
                log.info("commerce_detail_ineligible sku={}", skuId);
                return RuntimeProductDetailLookup.ineligible();
            }
            case FOUND -> { /* compose below */ }
        }
        CatalogProductDetailFacts facts = catalogLookup.facts();

        ProductCardBaseProjection base = baseRead.findBySku(skuId).orElseGet(() -> {
            log.warn("commerce_detail_base_missing sku={}", skuId);
            return degradedBase(facts);
        });
        RuntimeProductCard card = enricher.enrichOne(base, location);

        Optional<MediaSet> chosen = MediaSelection.selectFor(media, facts.skuId(), facts.productId());
        String primaryImageUrl = null;
        List<RuntimeProductImage> gallery = List.of();
        if (chosen.isPresent() && !chosen.get().assets().isEmpty()) {
            if (!mediaUrls.isConfigured()) {
                // readiness gap degrades IMAGERY ONLY; price/stock/attributes still compose
                log.warn("commerce_media_unconfigured: media base URL absent; detail imagery omitted");
            } else {
                MediaSet set = chosen.get();
                List<RuntimeProductImage> images = new ArrayList<>();
                for (MediaAsset asset : set.orderedAssets()) {
                    // a malformed persisted key throws typed InvalidMediaException here —
                    // corruption is never silently hidden (resolver is the single validator)
                    images.add(new RuntimeProductImage(
                            mediaUrls.resolve(asset.assetKey()), asset.role(),
                            asset.sortOrder(), asset.altText(), asset.width(), asset.height()));
                }
                gallery = List.copyOf(images);
                primaryImageUrl = set.primary()
                        .map(a -> mediaUrls.resolve(a.assetKey())).orElse(null);
            }
        }
        return RuntimeProductDetailLookup.found(
                new RuntimeProductDetail(card, facts.attributes(), primaryImageUrl, gallery));
    }

    /**
     * In-memory stand-in when an ELIGIBLE product has no {@code product_card_base} row yet:
     * identity from Catalog facts, {@code PriceStatus.MISSING} (amounts were NOT observed — a
     * fresh Pricing read here would fork the projection's composition rules), no media key.
     * Never persisted; buyability fails closed through the unmodified PR-08 rules.
     *
     * <p><b>Bounds reconciliation (PR-09 review, HIGH):</b> the Catalog WRITE path does not cap
     * {@code title}/{@code brandCode}/{@code verticalId} length, but {@link ProductCardBaseProjection}
     * enforces technical sanity ceilings (MAX_TITLE/MAX_ID). An eligible product that exceeds them
     * can never get a persisted row (rebuild fails identically), so it ALWAYS lands here — and a
     * raw construction would throw {@code IllegalArgumentException}, turning a data-quality state
     * into a caller-shaped error and breaking the documented "missing row → degraded FOUND" gate.
     * The stand-in therefore CLAMPS to the same ceilings the persisted card would impose anyway
     * (so PDP loses no information relative to the card contract) and logs the condition — this is
     * OBSERVED data-quality debt, not a silent truncation. Closing the write-side gap belongs to a
     * Catalog validation change, tracked separately.
     */
    private static ProductCardBaseProjection degradedBase(CatalogProductDetailFacts facts) {
        String title = clamp(facts.title(), ProductCardBaseProjection.MAX_TITLE);
        String brandCode = clamp(facts.brandCode(), ProductCardBaseProjection.MAX_ID);
        String verticalId = clamp(facts.verticalId(), ProductCardBaseProjection.MAX_ID);
        if (!title.equals(facts.title())
                || !Objects.equals(brandCode, facts.brandCode())
                || !Objects.equals(verticalId, facts.verticalId())) {
            log.warn("commerce_detail_facts_over_bounds sku={} — degraded stand-in clamped to card ceilings",
                    facts.skuId());
        }
        return new ProductCardBaseProjection(
                facts.skuId(), facts.productId(), title, brandCode,
                verticalId, PriceStatus.MISSING,
                null, null, null, null,
                facts.catalogVersion(), null, null, 1L);
    }

    private static String clamp(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
