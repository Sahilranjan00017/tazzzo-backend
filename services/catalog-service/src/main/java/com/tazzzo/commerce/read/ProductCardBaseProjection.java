package com.tazzzo.commerce.read;

import com.tazzzo.pricing.PriceStatus;

import java.util.Objects;

/**
 * The GLOBAL, LOCATION-AGNOSTIC materialized card (PR-07 / Phase 3.1). A DERIVED, DISPOSABLE
 * read model combining ONLY authoritative facts from Catalog (identity/taxonomy), Pricing
 * (paise/MRP) and Media (asset REFERENCES) — it owns NO business truth: when it disagrees with a
 * source, the source wins, and the row may be rebuilt, repaired or deleted at any time without
 * losing anything.
 *
 * <p><b>ABSOLUTE RULE (BLOCKER boundary, test-enforced):</b> ZERO location/customer state —
 * no stock/availability/reservations, no serviceability/areas/fulfillment locations, no
 * ETA/delivery promise, no buyability, no pincode-anything. Those are REQUEST-TIME enrichment in
 * the later Commerce Read composition, which maps
 * {@code base ⊕ ServiceabilityResolution ⊕ InventoryLookup → ProductCardDto}. This record is
 * deliberately NOT the public DTO and is never serialized to clients.
 *
 * <p><b>Price semantics (STEP 8):</b> amounts are persisted ONLY when canonical Pricing is
 * {@code ACTIVE}; for MISSING/INACTIVE/NOT_YET_EFFECTIVE/EXPIRED the amounts are null and the
 * status is recorded — a price is never fabricated, and base-row existence never implies
 * buyability.
 *
 * <p><b>Media semantics (STEP 9):</b> only the storage-neutral PRIMARY {@code assetKey} is
 * persisted — never a resolved URL, so a CDN/base change needs no projection rebuild.
 *
 * <p><b>Content vs metadata:</b> {@link #contentEquals} compares BUSINESS content only —
 * projectionVersion and the source-version metadata are excluded, so a source write that changed
 * nothing consumer-visible rebuilds as a NOOP (no version churn). Consequence, documented:
 * {@code catalogVersion/priceVersion/mediaVersion} record the sources of the LAST
 * CONTENT-CHANGING build.
 */
public record ProductCardBaseProjection(
        String skuId,
        String productId,
        String title,
        String brandCode,
        String verticalId,
        PriceStatus priceStatus,
        Long sellingPricePaise,
        Long mrpPaise,
        String currency,
        String primaryAssetKey,
        long catalogVersion,
        Long priceVersion,
        Long mediaVersion,
        long projectionVersion
) {
    /** Technical bounds (PR-07 review, STEP 12) — derived rows still refuse pathological values. */
    static final int MAX_ID = 128;
    static final int MAX_TITLE = 500;

    public ProductCardBaseProjection {
        requireId(skuId, "skuId");
        requireId(productId, "productId");
        if (title == null || title.isBlank() || title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("title required (max " + MAX_TITLE + " chars)");
        }
        if (brandCode != null && brandCode.length() > MAX_ID) {
            throw new IllegalArgumentException("brandCode exceeds " + MAX_ID);
        }
        if (verticalId != null && verticalId.length() > MAX_ID) {
            throw new IllegalArgumentException("verticalId exceeds " + MAX_ID);
        }
        if (primaryAssetKey != null && !com.tazzzo.media.MediaAsset.isSafeKey(primaryAssetKey)) {
            // Media-safe by construction upstream; re-checked so a corrupt persisted key fails loudly.
            throw new IllegalArgumentException("unsafe primaryAssetKey");
        }
        Objects.requireNonNull(priceStatus, "priceStatus required");
        boolean priced = sellingPricePaise != null;
        if (priced != (mrpPaise != null) || priced != (currency != null)) {
            throw new IllegalArgumentException("price fields must be all-present or all-absent");
        }
        if (priced && priceStatus != PriceStatus.ACTIVE) {
            throw new IllegalArgumentException("amounts may only be persisted for ACTIVE pricing");
        }
        if (priced && (sellingPricePaise < 0 || mrpPaise < 0 || mrpPaise < sellingPricePaise)) {
            throw new IllegalArgumentException("invalid paise amounts");
        }
        if (priced) {
            // Currency must be the canonical enum vocabulary — arbitrary strings fail loudly
            // (STEP 13): persisted corruption must never round-trip into a valid-looking card.
            com.tazzzo.common.money.Currency.valueOf(currency);
        }
        if (projectionVersion < 1) throw new IllegalArgumentException("projectionVersion must be positive");
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_ID
                || value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException(name + " required: non-blank, no control chars, max " + MAX_ID);
        }
    }

    /** Business-content equality: excludes projectionVersion AND source-version metadata. */
    public boolean contentEquals(ProductCardBaseProjection other) {
        return other != null
                && skuId.equals(other.skuId)
                && productId.equals(other.productId)
                && title.equals(other.title)
                && Objects.equals(brandCode, other.brandCode)
                && Objects.equals(verticalId, other.verticalId)
                && priceStatus == other.priceStatus
                && Objects.equals(sellingPricePaise, other.sellingPricePaise)
                && Objects.equals(mrpPaise, other.mrpPaise)
                && Objects.equals(currency, other.currency)
                && Objects.equals(primaryAssetKey, other.primaryAssetKey);
    }
}
