package com.tazzzo.inventory;

/**
 * Administrative/source absolute set of a SKU's stock at one fulfillment location (STEP 8).
 * {@code expectedVersion} null = create (version 1, reserved initialised to 0); a value = CAS
 * update to version+1. An update never touches {@code reserved} (owned by the reservation flow)
 * and is rejected if the new {@code onHand} would fall below current {@code reserved} — the
 * available quantity can never go negative.
 *
 * <p><b>{@code active} ownership:</b> this command NEVER mutates {@code active} (create writes
 * {@code true}; update leaves it untouched). Deactivating/reactivating a row (delisting a SKU at
 * a store) is a distinct lifecycle operation that will arrive as its own explicit, CAS-guarded,
 * audited command when a real need exists — deliberately not smuggled into an absolute stock set.
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
