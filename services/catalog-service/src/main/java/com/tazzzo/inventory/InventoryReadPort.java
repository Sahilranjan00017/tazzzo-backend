package com.tazzzo.inventory;

/**
 * Internal read boundary for canonical inventory (STEP 12). Returns domain values
 * ({@link InventoryLookup}), never Mongo documents or commerce.api DTOs. Commerce Read (PR-08)
 * depends on THIS port; this module never depends on commerce.read. No public controller exists
 * in PR-04, and no public surface ever accepts a client-supplied fulfillmentLocationId.
 */
public interface InventoryReadPort {

    InventoryLookup findInventory(String skuId, String fulfillmentLocationId);
}
