package com.tazzzo.inventory;

/**
 * Canonical inventory identity (ADR-004, Phase 3.1): one row per
 * {@code (skuId, fulfillmentLocationId)}. Inventory is NEVER keyed by productId alone, and
 * {@code fulfillmentLocationId} is INTERNAL — it appears in no public DTO; Serviceability
 * resolves it server-side from the customer location (future PR-06/PR-08).
 */
public record InventoryKey(String skuId, String fulfillmentLocationId) {
    public InventoryKey {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId required");
        }
        if (fulfillmentLocationId == null || fulfillmentLocationId.isBlank()) {
            throw new IllegalArgumentException("fulfillmentLocationId required");
        }
    }
}
