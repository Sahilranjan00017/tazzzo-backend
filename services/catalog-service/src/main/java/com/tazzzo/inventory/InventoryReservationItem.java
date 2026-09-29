package com.tazzzo.inventory;

/**
 * PR-14A — one SKU/quantity line of a reservation. Quantity bounds mirror
 * {@link InventoryService#MAX_QUANTITY} (the same fat-finger ceiling every other inventory
 * quantity in this module already enforces).
 */
public record InventoryReservationItem(String skuId, long quantity) {

    public InventoryReservationItem {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId required");
        }
        if (skuId.length() > 128) {
            throw new IllegalArgumentException("skuId exceeds 128 chars");
        }
        if (quantity < 1 || quantity > InventoryService.MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "quantity must be in [1," + InventoryService.MAX_QUANTITY + "]: " + quantity);
        }
    }
}
