package com.tazzzo.inventory;

/**
 * Canonical inventory identity (ADR-004, Phase 3.1): one row per
 * {@code (skuId, fulfillmentLocationId)}. Inventory is NEVER keyed by productId alone, and
 * {@code fulfillmentLocationId} is INTERNAL — it appears in no public DTO; Serviceability
 * resolves it server-side from the customer location (future PR-06/PR-08).
 */
public record InventoryKey(String skuId, String fulfillmentLocationId) {
    public InventoryKey {
        requireId(skuId, "skuId");
        requireId(fulfillmentLocationId, "fulfillmentLocationId");
    }

    /** Same bounds as the serviceability route's location id: it must be the same string on both sides of the join. */
    static final int MAX_ID = 128;

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        if (value.length() > MAX_ID || !value.equals(value.trim()) || value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException(name + " invalid: trimmed, no control chars, max " + MAX_ID);
        }
    }
}
