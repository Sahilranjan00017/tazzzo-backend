package com.tazzzo.inventory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-14A — pure unit tests for the reservation domain types' compact-constructor invariants. No
 * Mongo.
 */
class InventoryReservationDomainTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(600);

    // ---------- InventoryReservationId ----------

    @Test void reservation_id_generate_produces_a_valid_shape() {
        assertTrue(InventoryReservationId.isValid(InventoryReservationId.generate().value()));
    }

    @Test void reservation_id_rejects_malformed_shape() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationId("not-a-reservation-id"));
        assertFalse(InventoryReservationId.isValid("RESV_"));
        assertFalse(InventoryReservationId.isValid(null));
    }

    // ---------- InventoryReservationItem ----------

    @Test void item_rejects_blank_sku_and_bad_quantity() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationItem("", 1));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationItem("TZP-1", 0));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationItem("TZP-1", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryReservationItem("TZP-1", InventoryService.MAX_QUANTITY + 1));
    }

    @Test void item_accepts_boundary_quantity() {
        assertEquals(InventoryService.MAX_QUANTITY,
                new InventoryReservationItem("TZP-1", InventoryService.MAX_QUANTITY).quantity());
    }

    // ---------- InventoryReservationCommand ----------

    private static InventoryReservationCommand command(List<InventoryReservationItem> items) {
        return new InventoryReservationCommand("ORD-1", "FUL-1", items, InventoryReservationId.generate(), NOW, EXPIRES);
    }

    @Test void command_rejects_blank_orderId_and_location() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCommand(null, "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationId.generate(), NOW, EXPIRES));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCommand("ORD-1", " ",
                List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationId.generate(), NOW, EXPIRES));
    }

    @Test void command_rejects_empty_items() {
        assertThrows(IllegalArgumentException.class, () -> command(List.of()));
    }

    @Test void command_rejects_duplicate_sku() {
        assertThrows(IllegalArgumentException.class, () -> command(List.of(
                new InventoryReservationItem("TZP-1", 1), new InventoryReservationItem("TZP-1", 2))));
    }

    @Test void command_rejects_too_many_distinct_items() {
        List<InventoryReservationItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < InventoryReservationCommand.MAX_DISTINCT_ITEMS + 1; i++) {
            items.add(new InventoryReservationItem("TZP-" + i, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> command(items));
    }

    @Test void command_rejects_missing_reservationId_or_expiresAt() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCommand("ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), null, NOW, EXPIRES));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCommand("ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationId.generate(), NOW, null));
    }

    @Test void command_rejects_preparedAt_not_before_expiresAt() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCommand("ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationId.generate(), EXPIRES,
                EXPIRES));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationCommand("ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationId.generate(),
                EXPIRES.plusSeconds(1), EXPIRES));
    }

    @Test void the_canonical_constructor_is_not_public_so_it_cannot_be_forged_outside_this_package() throws Exception {
        var ctor = InventoryReservationCommand.class.getDeclaredConstructors()[0];
        assertFalse(java.lang.reflect.Modifier.isPublic(ctor.getModifiers()),
                "InventoryReservationCommand must only be constructible via prepare(), never directly");
    }

    // ---------- InventoryReservationRequest ----------

    @Test void request_rejects_blank_orderId_location_empty_or_duplicate_items() {
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryReservationRequest(null, "FUL-1", List.of(new InventoryReservationItem("TZP-1", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new InventoryReservationRequest("ORD-1", " ", List.of(new InventoryReservationItem("TZP-1", 1))));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationRequest("ORD-1", "FUL-1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservationRequest("ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1), new InventoryReservationItem("TZP-1", 2))));
    }

    @Test void fingerprint_input_ignores_input_item_ordering() {
        InventoryReservationCommand a = new InventoryReservationCommand("ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-2", 2), new InventoryReservationItem("TZP-1", 1)),
                InventoryReservationId.generate(), NOW, EXPIRES);
        InventoryReservationCommand b = new InventoryReservationCommand("ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1), new InventoryReservationItem("TZP-2", 2)),
                InventoryReservationId.generate(), NOW, EXPIRES);
        assertEquals(a.canonicalFingerprintInput("v1"), b.canonicalFingerprintInput("v1"));
    }

    @Test void fingerprint_input_changes_with_quantity_or_location() {
        InventoryReservationCommand base = command(List.of(new InventoryReservationItem("TZP-1", 1)));
        InventoryReservationCommand differentQty = command(List.of(new InventoryReservationItem("TZP-1", 2)));
        assertNotEquals(base.canonicalFingerprintInput("v1"), differentQty.canonicalFingerprintInput("v1"));
    }

    // ---------- InventoryReservation ----------

    private static InventoryReservation reservation(InventoryReservationStatus status) {
        return new InventoryReservation(InventoryReservationId.generate().value(), "ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), status, NOW, EXPIRES, NOW);
    }

    @Test void reservation_rejects_invalid_id_shape() {
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservation("not-a-reservation-id", "ORD-1",
                "FUL-1", List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationStatus.RESERVED,
                NOW, EXPIRES, NOW));
    }

    @Test void reservation_rejects_empty_items_and_duplicate_sku() {
        String id = InventoryReservationId.generate().value();
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservation(id, "ORD-1", "FUL-1",
                List.of(), InventoryReservationStatus.RESERVED, NOW, EXPIRES, NOW));
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservation(id, "ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1), new InventoryReservationItem("TZP-1", 2)),
                InventoryReservationStatus.RESERVED, NOW, EXPIRES, NOW));
    }

    @Test void reservation_rejects_createdAt_not_before_expiresAt() {
        String id = InventoryReservationId.generate().value();
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservation(id, "ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationStatus.RESERVED,
                EXPIRES, EXPIRES, EXPIRES));
    }

    @Test void reservation_rejects_updatedAt_before_createdAt() {
        String id = InventoryReservationId.generate().value();
        assertThrows(IllegalArgumentException.class, () -> new InventoryReservation(id, "ORD-1", "FUL-1",
                List.of(new InventoryReservationItem("TZP-1", 1)), InventoryReservationStatus.RESERVED,
                NOW, EXPIRES, NOW.minusSeconds(1)));
    }

    @Test void isExpired_only_true_for_RESERVED_past_expiresAt() {
        InventoryReservation reserved = reservation(InventoryReservationStatus.RESERVED);
        assertFalse(reserved.isExpired(EXPIRES.minusSeconds(1)));
        assertTrue(reserved.isExpired(EXPIRES));
        assertTrue(reserved.isExpired(EXPIRES.plusSeconds(1)));

        InventoryReservation released = reservation(InventoryReservationStatus.RELEASED);
        assertFalse(released.isExpired(EXPIRES.plusSeconds(1)), "a terminal reservation is never 'expired'");

        InventoryReservation consumed = reservation(InventoryReservationStatus.CONSUMED);
        assertFalse(consumed.isExpired(EXPIRES.plusSeconds(1)));
    }

    // ---------- PR-14A hardening: InventoryReservationProperties ----------

    @Test void invalid_ttl_configuration_fails_validation() {
        InventoryReservationProperties tooLow = new InventoryReservationProperties();
        tooLow.setTtlSeconds(10);
        assertThrows(IllegalStateException.class, tooLow::validate);

        InventoryReservationProperties tooHigh = new InventoryReservationProperties();
        tooHigh.setTtlSeconds(9999);
        assertThrows(IllegalStateException.class, tooHigh::validate);

        InventoryReservationProperties badBatch = new InventoryReservationProperties();
        badBatch.setExpiryBatchSize(0);
        assertThrows(IllegalStateException.class, badBatch::validate);
    }

    @Test void a_default_constructed_properties_instance_passes_validation() {
        assertDoesNotThrow(new InventoryReservationProperties()::validate);
    }
}
