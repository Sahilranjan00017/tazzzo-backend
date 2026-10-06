package com.tazzzo.customer.cart;

import java.util.List;

/**
 * PR-12C — the public cart. {@code subtotalPaise} is the sum of PRICED lines at CURRENT prices —
 * NOT a final payable total (no delivery/platform fee, tax, tip or discount; those belong to
 * Checkout). Nothing here reserves stock or locks a price. Never exposes fulfillmentLocationId, an
 * internal inventory location, routing detail, cache keys or Mongo fields. {@code freshness} is the cart age band
 * ({@code FRESH} under 24 h since the last change, {@code REVALIDATE} from 24 h through 7 days; older carts expire).
 */
public record CartResponseDto(long version, List<Item> items, int itemCount, int distinctItemCount,
                              long subtotalPaise, String expiresAt, String freshness, String requestId) {

    public record Item(String skuId, int quantity, String addedAt, String updatedAt, Product product,
                       Price price, Availability availability, Long lineTotalPaise, boolean buyable,
                       List<CartIssue> issues) {
    }

    public record Product(String title, String brandCode, String imageUrl) {
    }

    public record Price(long unitPricePaise, Long mrpPaise, String currency) {
    }

    /** {@code serviceable} null = unknown (no location); {@code stockState} UNKNOWN is never coerced. */
    public record Availability(String stockState, int maxOrderQuantity, Boolean serviceable) {
    }
}
