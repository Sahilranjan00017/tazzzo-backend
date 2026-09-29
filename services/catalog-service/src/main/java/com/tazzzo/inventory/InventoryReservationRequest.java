package com.tazzzo.inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PR-14A hardening (M1) — what a caller (a future {@code customer.order}) is actually allowed to
 * ask for: WHICH order, WHICH location, WHICH SKUs/quantities. Nothing about the reservation's
 * identity or lifetime — that is Inventory's own policy, computed by
 * {@link InventoryReservationPort#prepare}, never accepted from a caller. Bounded like Cart's own
 * item limit (50 distinct SKUs).
 */
public record InventoryReservationRequest(String orderId, String fulfillmentLocationId,
                                          List<InventoryReservationItem> items) {

    public InventoryReservationRequest {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId required");
        }
        if (fulfillmentLocationId == null || fulfillmentLocationId.isBlank()) {
            throw new IllegalArgumentException("fulfillmentLocationId required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must be non-empty");
        }
        if (items.size() > PreparedInventoryReservation.MAX_DISTINCT_ITEMS) {
            throw new IllegalArgumentException(
                    "items exceeds the limit of " + PreparedInventoryReservation.MAX_DISTINCT_ITEMS);
        }
        Set<String> seen = new HashSet<>();
        for (InventoryReservationItem item : items) {
            if (!seen.add(item.skuId())) {
                throw new IllegalArgumentException("duplicate skuId in reservation request: " + item.skuId());
            }
        }
        items = List.copyOf(items);
    }
}
