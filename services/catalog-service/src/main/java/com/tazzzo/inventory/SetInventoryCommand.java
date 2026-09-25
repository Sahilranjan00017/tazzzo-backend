package com.tazzzo.inventory;

/**
 * Administrative/source absolute set of a SKU's stock at one fulfillment location (STEP 8).
 * {@code expectedVersion} null = create (version 1, reserved initialised to 0); a value = CAS
 * update to version+1. An update never touches {@code reserved} (owned by the reservation flow)
 * and is rejected if the new {@code onHand} would fall below current {@code reserved} — the
 * available quantity can never go negative.
 */
public record SetInventoryCommand(
        String skuId,
        String fulfillmentLocationId,
        long onHand,
        long lowStockThreshold,
        long maxPurchasable,
        String source,
        Long expectedVersion
) { }
