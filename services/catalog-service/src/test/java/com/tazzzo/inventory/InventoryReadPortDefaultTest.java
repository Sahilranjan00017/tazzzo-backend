package com.tazzzo.inventory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-08 review, STEP 4: the {@link InventoryReadPort} default batch must honour its OWN javadoc —
 * duplicates deduplicated (one underlying read each, not one per occurrence) and every requested
 * SKU present in the result. The original {@code putIfAbsent(skuId, findInventory(...))} shape
 * evaluated arguments eagerly and re-read every duplicate; these tests pin the fixed behaviour.
 */
class InventoryReadPortDefaultTest {

    /** Functional-interface stub that counts every point read. */
    private static final class CountingPort implements InventoryReadPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public InventoryLookup findInventory(String skuId, String fulfillmentLocationId) {
            calls.add(skuId);
            return InventoryLookup.missing();
        }
    }

    @Test void duplicates_cost_exactly_one_read_each() {
        CountingPort port = new CountingPort();
        Map<String, InventoryLookup> out =
                port.findInventoryBatch(List.of("SKU-A", "SKU-A", "SKU-B", "SKU-A"), "FL-BLR-01");

        assertEquals(List.of("SKU-A", "SKU-B"), port.calls,
                "each distinct SKU must be read exactly once, first-seen order");
        assertEquals(2, out.size());
        assertTrue(out.containsKey("SKU-A"));
        assertTrue(out.containsKey("SKU-B"));
    }

    @Test void every_requested_sku_is_present_in_result() {
        CountingPort port = new CountingPort();
        Map<String, InventoryLookup> out =
                port.findInventoryBatch(List.of("SKU-X", "SKU-Y"), "FL-BLR-01");
        assertEquals(InventoryLookup.Status.MISSING, out.get("SKU-X").status());
        assertEquals(InventoryLookup.Status.MISSING, out.get("SKU-Y").status());
    }

    @Test void empty_input_yields_empty_map_and_zero_reads() {
        CountingPort port = new CountingPort();
        assertTrue(port.findInventoryBatch(List.of(), "FL-BLR-01").isEmpty());
        assertTrue(port.calls.isEmpty());
    }

    @Test void null_collection_rejected_typed() {
        CountingPort port = new CountingPort();
        assertThrows(NullPointerException.class,
                () -> port.findInventoryBatch(null, "FL-BLR-01"));
        assertTrue(port.calls.isEmpty(), "validation must happen before any read");
    }
}
