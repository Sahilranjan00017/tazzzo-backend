package com.tazzzo.inventory;

/**
 * TEST-ONLY bridge to the package-private {@link InventoryService#tryReserve} primitive
 * (PR-04 review, Option A). Lives in test sources within the {@code com.tazzzo.inventory}
 * package so integration tests can prove the atomic oversell-safe mechanics while production
 * code outside the module cannot reach the primitive until the reservation lifecycle
 * (reservationId, expiry, release, recovery, idempotency) exists.
 */
public final class InventoryTestAccess {

    private InventoryTestAccess() { }

    public static boolean tryReserve(InventoryService service, String skuId,
                                     String fulfillmentLocationId, long qty) {
        return service.tryReserve(skuId, fulfillmentLocationId, qty);
    }
}
