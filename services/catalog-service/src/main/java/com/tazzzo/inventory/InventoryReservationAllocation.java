package com.tazzzo.inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PR-14B (preparation-contract evolution) — the caller-supplied, semantic "what/where" of a
 * reservation: WHICH location, WHICH SKUs/quantities. Nothing about identity or lifetime — that
 * remains Inventory's own policy, carried separately by {@link PreparedInventoryReservation}.
 *
 * <p>Unlike {@code PreparedInventoryReservation}, this type is freely caller-constructible: it
 * protects no Inventory-owned fact, so there is nothing here for Inventory to guard against a
 * caller inventing. A future {@code customer.order} constructs this fresh, INSIDE its own
 * transaction callback, from whatever fulfillment location its OWN just-verified serviceability
 * read returned THIS attempt — deliberately allowed to differ across a {@code Tx.call} driver
 * retry (see {@link InventoryReservationService#reserve}'s javadoc): an aborted attempt commits
 * nothing, so a later attempt legitimately allocating against a newly-current route is not
 * "changing" anything that ever existed.
 *
 * <p>Bounded like Cart's own item limit (50 distinct SKUs) — the same ceiling the retired
 * {@code InventoryReservationRequest} enforced.
 */
public record InventoryReservationAllocation(String fulfillmentLocationId, List<InventoryReservationItem> items) {

    public InventoryReservationAllocation {
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
                throw new IllegalArgumentException("duplicate skuId in allocation: " + item.skuId());
            }
        }
        items = List.copyOf(items);
    }

    /**
     * PR-14B — the canonical, order-independent fingerprint input: items sorted by skuId, so the
     * caller's original request ordering can never change identity. {@code fingerprintVersion} is a
     * manual tag ("v1") — bump it, never overload its meaning, if a future field becomes
     * meaning-bearing (documented on {@link InventoryReservationService}).
     */
    String canonicalFingerprintInput(String fingerprintVersion, String orderId) {
        StringBuilder sb = new StringBuilder(fingerprintVersion).append('|').append(orderId).append('|')
                .append(fulfillmentLocationId);
        items.stream().sorted(java.util.Comparator.comparing(InventoryReservationItem::skuId))
                .forEach(i -> sb.append('|').append(i.skuId()).append(':').append(i.quantity()));
        return sb.toString();
    }
}
