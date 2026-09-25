package com.tazzzo.inventory;

import com.tazzzo.commerce.contract.StockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 7/23: exact stock-state derivation and boundaries. */
class StockStateDerivationTest {

    private InventoryRecord rec(long onHand, long reserved, long threshold) {
        return new InventoryRecord("TZP-1", "FL-BLR-01", onHand, reserved, threshold, 10, 1, true);
    }

    @Test void zero_available_is_out_of_stock() {
        assertEquals(StockState.OUT_OF_STOCK, rec(0, 0, 3).stockState());
        assertEquals(StockState.OUT_OF_STOCK, rec(5, 5, 3).stockState()); // fully reserved
    }

    @Test void available_at_threshold_is_low_stock_boundary() {
        assertEquals(StockState.LOW_STOCK, rec(3, 0, 3).stockState()); // available == threshold
        assertEquals(StockState.LOW_STOCK, rec(8, 5, 3).stockState()); // 3 available == threshold
    }

    @Test void available_below_threshold_is_low_stock() {
        assertEquals(StockState.LOW_STOCK, rec(2, 0, 3).stockState());
        assertEquals(1, rec(2, 1, 3).available());
        assertEquals(StockState.LOW_STOCK, rec(2, 1, 3).stockState());
    }

    @Test void available_above_threshold_is_in_stock() {
        assertEquals(StockState.IN_STOCK, rec(4, 0, 3).stockState());
        assertEquals(StockState.IN_STOCK, rec(100, 50, 3).stockState());
    }

    @Test void zero_threshold_means_any_stock_is_in_stock() {
        assertEquals(StockState.IN_STOCK, rec(1, 0, 0).stockState());
        assertEquals(StockState.OUT_OF_STOCK, rec(0, 0, 0).stockState());
    }

    @Test void available_is_on_hand_minus_reserved() {
        assertEquals(7, rec(10, 3, 2).available());
    }

    @Test void record_state_is_never_unknown() {
        // UNKNOWN belongs to InventoryLookup.MISSING / composition boundaries, not to a valid row.
        for (long onHand = 0; onHand <= 4; onHand++) {
            assertNotEquals(StockState.UNKNOWN, rec(onHand, 0, 2).stockState());
        }
    }
}
