package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.StockState;

import java.util.Objects;

/**
 * The INTERNAL runtime card (PR-08, STEP 31/32): global base facts ⊕ request-time location
 * enrichment. This is deliberately NOT the public {@code ProductCardDto} — the frozen DAG says
 * {@code commerce.api → commerce.read}, never the reverse, so commerce.read produces this
 * internal model and the future API layer (PR-10) maps
 * {@code RuntimeProductCard → ProductCardDto} (adding public-only constants like
 * {@code sponsored=false} there).
 *
 * <p><b>ONE-WAY boundary (STEP 4):</b> everything location-derived here (stockState,
 * lowStockRemaining, maxOrderQuantity, serviceable, buyable) exists ONLY in this runtime value —
 * it is NEVER persisted back into {@code product_card_base}. And the internal
 * {@code fulfillmentLocationId} that drove inventory reads is deliberately NOT a field: it lives
 * only inside the composer's local scope.
 *
 * <p>Optional merchandising fields not yet backed by authoritative sources (brandName, packSize,
 * unit, offerSummary, badges, rating) are deliberately ABSENT from this model — nothing is
 * fabricated to make a UI look full (STEP 14); they join when their owning sources exist.
 */
public record RuntimeProductCard(
        String skuId,
        String productId,
        String title,
        String brandCode,
        String verticalId,
        String thumbnailUrl,
        Long sellingPricePaise,
        Long mrpPaise,
        Long discountAmountPaise,
        Integer discountPercent,
        StockState stockState,
        Integer lowStockRemaining,
        int maxOrderQuantity,
        int minimumOrderQuantity,
        Boolean serviceable,
        Integer etaMinutesMin,
        Integer etaMinutesMax,
        boolean buyable
) {
    public RuntimeProductCard {
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        Objects.requireNonNull(stockState, "stockState required");
        if (maxOrderQuantity < 0) throw new IllegalArgumentException("maxOrderQuantity must be >= 0");
        if (minimumOrderQuantity < 1) throw new IllegalArgumentException("minimumOrderQuantity must be >= 1");
        if (discountPercent != null && (discountPercent < 0 || discountPercent > 99)) {
            throw new IllegalArgumentException("discountPercent must be within the frozen 0..99 contract");
        }
        if (buyable && (sellingPricePaise == null || stockState == StockState.OUT_OF_STOCK
                || stockState == StockState.UNKNOWN || !Boolean.TRUE.equals(serviceable)
                || maxOrderQuantity < minimumOrderQuantity)) {
            throw new IllegalArgumentException("buyable=true violates the frozen buyability formula");
        }
    }
}
