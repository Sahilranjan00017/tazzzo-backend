package com.tazzzo.commerce.read;

/**
 * The consumer-safe Catalog facts one card is built from (PR-07). Sourced ONLY from products that
 * pass {@code ConsumerEligibility} — the single ratified predicate (lifecycle=active,
 * classification=confirmed, non-holding vertical, admitted product type). Never draft/admin data.
 *
 * <p>{@code skuId}/{@code productId} are carried separately even though they are equal at launch:
 * Pricing/Inventory are SKU-oriented and future variants may diverge — nothing here may ever
 * assume equality.
 */
public record CatalogCardFacts(
        String skuId,
        String productId,
        String title,
        String brandCode,
        String verticalId,
        long catalogVersion
) {
    public CatalogCardFacts {
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        if (catalogVersion < 0) throw new IllegalArgumentException("catalogVersion must be >= 0");
    }
}
