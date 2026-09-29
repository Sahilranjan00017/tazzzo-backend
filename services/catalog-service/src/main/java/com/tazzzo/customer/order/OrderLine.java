package com.tazzzo.customer.order;

/**
 * PR-14B — the immutable, transactionally-validated commercial line an {@link Order} snapshots at
 * creation. {@code title}/{@code brandCode} come from the session-aware Catalog eligibility read
 * performed INSIDE the same transaction the Order commits in — never from the source
 * {@code CheckoutQuote} (which carries no display data) and never re-read afterward, so a later
 * product rename/re-brand never changes a committed Order's history.
 *
 * <p>Deliberately minimal: no {@code image}, no full product card, no {@code verticalId}, no
 * {@code catalogVersion}, no {@code productId}. At launch {@code skuId == productId}
 * ({@code CatalogCardFacts}'s own documented convention) — Order's concern is the commercial line
 * at SKU granularity, not variant/product grouping, so a separate {@code productId} would be pure
 * duplication today. If a future SKU/product-variant split ever makes {@code productId}
 * meaningfully different AND Order needs it, that is an additive field on a new document version,
 * not something to pre-build speculatively now.
 */
public record OrderLine(String skuId, String title, String brandCode, int quantity, long unitPricePaise,
                        long lineTotalPaise) {

    public OrderLine {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title required");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1: " + quantity);
        }
        if (unitPricePaise < 0) {
            throw new IllegalArgumentException("unitPricePaise must be >= 0: " + unitPricePaise);
        }
        if (lineTotalPaise < 0) {
            throw new IllegalArgumentException("lineTotalPaise must be >= 0: " + lineTotalPaise);
        }
        if (lineTotalPaise != Math.multiplyExact(unitPricePaise, (long) quantity)) {
            throw new IllegalArgumentException(
                    "lineTotalPaise does not equal unitPricePaise * quantity for " + skuId);
        }
        // brandCode is nullable -- CatalogCardFacts.brandCode carries the same nullability, and
        // this snapshot must never invent a fact Catalog itself does not assert.
    }
}
