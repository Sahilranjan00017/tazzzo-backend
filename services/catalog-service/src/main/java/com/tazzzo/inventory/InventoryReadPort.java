package com.tazzzo.inventory;

/**
 * Internal read boundary for canonical inventory (STEP 12). Returns domain values
 * ({@link InventoryLookup}), never Mongo documents or commerce.api DTOs. Commerce Read (PR-08)
 * depends on THIS port; this module never depends on commerce.read. No public controller exists
 * in PR-04, and no public surface ever accepts a client-supplied fulfillmentLocationId.
 */
public interface InventoryReadPort {

    InventoryLookup findInventory(String skuId, String fulfillmentLocationId);

    /**
     * Batch read for page enrichment (PR-08): every requested SKU is present in the result —
     * a SKU with no row maps to {@link InventoryLookup.Status#MISSING}, an inactive row to
     * {@code INACTIVE} — so callers never confuse "not returned" with "no stock". Duplicate
     * input ids are deterministic (deduplicated). The result can never carry another
     * fulfillment location: every lookup is scoped to the single supplied location.
     *
     * <p>This default is the naive per-SKU fallback so functional-interface test stubs keep
     * working; {@link InventoryService} overrides it with ONE indexed query.
     */
    default java.util.Map<String, InventoryLookup> findInventoryBatch(
            java.util.Collection<String> skuIds, String fulfillmentLocationId) {
        java.util.Map<String, InventoryLookup> out = new java.util.LinkedHashMap<>();
        for (String skuId : skuIds) {
            out.putIfAbsent(skuId, findInventory(skuId, fulfillmentLocationId));
        }
        return out;
    }
}
