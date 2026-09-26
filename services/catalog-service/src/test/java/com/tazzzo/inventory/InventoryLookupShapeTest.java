package com.tazzzo.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-08 review, STEP 2 (HIGH): {@link InventoryLookup} structural invariants — an inconsistent
 * status/record pairing must be UNREPRESENTABLE, because the frozen buyable rule trusts
 * {@code PRESENT} to mean "an ACTIVE row exists". All six shape combinations are pinned.
 */
class InventoryLookupShapeTest {

    private InventoryRecord record(boolean active) {
        return new InventoryRecord("TZP-1", "FL-BLR-01", 10, 2, 3, 5, 1, active);
    }

    // -- the three LEGAL shapes --

    @Test void missing_with_null_record_accepted() {
        InventoryLookup lookup = InventoryLookup.missing();
        assertEquals(InventoryLookup.Status.MISSING, lookup.status());
        assertNull(lookup.record());
        assertFalse(lookup.isPresent());
    }

    @Test void present_with_active_record_accepted() {
        InventoryLookup lookup = InventoryLookup.of(InventoryLookup.Status.PRESENT, record(true));
        assertTrue(lookup.isPresent());
        assertTrue(lookup.presentRecord().isPresent());
    }

    @Test void inactive_with_inactive_record_accepted() {
        InventoryLookup lookup = InventoryLookup.of(InventoryLookup.Status.INACTIVE, record(false));
        assertFalse(lookup.isPresent());
        assertTrue(lookup.presentRecord().isEmpty());
    }

    // -- the three ILLEGAL shapes (each was constructible before the review fix) --

    @Test void present_with_inactive_record_rejected() {
        // The exact smuggling shape the HIGH finding targeted: PRESENT wrapping an inactive row
        // would sail past the buyable rule's status check.
        assertThrows(IllegalArgumentException.class,
                () -> InventoryLookup.of(InventoryLookup.Status.PRESENT, record(false)));
    }

    @Test void present_with_null_record_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> InventoryLookup.of(InventoryLookup.Status.PRESENT, null));
    }

    @Test void missing_with_record_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> InventoryLookup.of(InventoryLookup.Status.MISSING, record(true)));
    }

    @Test void inactive_with_active_record_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> InventoryLookup.of(InventoryLookup.Status.INACTIVE, record(true)));
    }

    @Test void inactive_with_null_record_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> InventoryLookup.of(InventoryLookup.Status.INACTIVE, null));
    }

    @Test void null_status_rejected() {
        assertThrows(NullPointerException.class, () -> InventoryLookup.of(null, null));
    }
}
