package com.tazzzo.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 23 UNIT: command + record validation, no Mongo. */
class InventoryValidationTest {

    private SetInventoryCommand cmd(String sku, String loc, long onHand, long threshold, long cap, Long ver) {
        return new SetInventoryCommand(sku, loc, onHand, threshold, cap, "seed", ver);
    }

    @Test void valid_inventory_accepted() {
        assertDoesNotThrow(() -> InventoryService.validateCommand(cmd("TZP-1", "FL-BLR-01", 50, 5, 10, null)));
    }

    @Test void zero_on_hand_is_valid() {
        assertDoesNotThrow(() -> InventoryService.validateCommand(cmd("TZP-1", "FL-BLR-01", 0, 5, 10, null)));
    }

    @Test void negative_on_hand_rejected() {
        assertThrows(InvalidInventoryException.class,
                () -> InventoryService.validateCommand(cmd("TZP-1", "FL-BLR-01", -1, 5, 10, null)));
    }

    @Test void negative_threshold_rejected() {
        assertThrows(InvalidInventoryException.class,
                () -> InventoryService.validateCommand(cmd("TZP-1", "FL-BLR-01", 10, -1, 10, null)));
    }

    @Test void negative_max_purchasable_rejected() {
        assertThrows(InvalidInventoryException.class,
                () -> InventoryService.validateCommand(cmd("TZP-1", "FL-BLR-01", 10, 5, -1, null)));
    }

    @Test void blank_sku_rejected() {
        assertThrows(InvalidInventoryException.class,
                () -> InventoryService.validateCommand(cmd(" ", "FL-BLR-01", 10, 5, 10, null)));
    }

    @Test void blank_location_rejected() {
        assertThrows(InvalidInventoryException.class,
                () -> InventoryService.validateCommand(cmd("TZP-1", "", 10, 5, 10, null)));
    }

    @Test void on_hand_above_sanity_ceiling_rejected() {
        assertThrows(InvalidInventoryException.class,
                () -> InventoryService.validateCommand(
                        cmd("TZP-1", "FL-BLR-01", InventoryService.MAX_QUANTITY + 1, 5, 10, null)));
    }

    @Test void on_hand_at_ceiling_allowed() {
        assertDoesNotThrow(() -> InventoryService.validateCommand(
                cmd("TZP-1", "FL-BLR-01", InventoryService.MAX_QUANTITY, 5, 10, null)));
    }

    @Test void non_positive_expected_version_rejected() {
        assertThrows(InvalidInventoryException.class,
                () -> InventoryService.validateCommand(cmd("TZP-1", "FL-BLR-01", 10, 5, 10, 0L)));
    }

    @Test void record_rejects_reserved_above_on_hand() {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryRecord("TZP-1", "FL-BLR-01", 5, 6, 2, 10, 1, true));
    }

    @Test void record_rejects_negative_reserved_and_version() {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryRecord("TZP-1", "FL-BLR-01", 5, -1, 2, 10, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryRecord("TZP-1", "FL-BLR-01", 5, 0, 2, 10, 0, true));
    }
}
