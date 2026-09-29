package com.tazzzo.inventory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PR-14A — the immutable, committed reservation header. A future {@code customer.order} caller
 * trusts this without re-deriving it, so — the same convention {@code CheckoutQuote} already
 * established — the compact constructor validates every invariant a caller relies on and fails
 * LOUD (never normalizes) on a violation, including when reconstructing from a corrupt Mongo
 * document. Carries no customer identity: Inventory knows which ORDER owns a reservation, never
 * which customer owns that order.
 */
public record InventoryReservation(String reservationId, String orderId, String fulfillmentLocationId,
                                   List<InventoryReservationItem> items, InventoryReservationStatus status,
                                   Instant createdAt, Instant expiresAt, Instant updatedAt) {

    public InventoryReservation {
        if (!InventoryReservationId.isValid(reservationId)) {
            throw new IllegalArgumentException("invalid reservationId shape");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId required");
        }
        if (fulfillmentLocationId == null || fulfillmentLocationId.isBlank()) {
            throw new IllegalArgumentException("fulfillmentLocationId required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must be non-empty");
        }
        Set<String> seen = new HashSet<>();
        for (InventoryReservationItem item : items) {
            if (!seen.add(item.skuId())) {
                throw new IllegalArgumentException("duplicate skuId in reservation: " + item.skuId());
            }
        }
        items = List.copyOf(items);
        if (status == null) {
            throw new IllegalArgumentException("status required");
        }
        if (createdAt == null || expiresAt == null || updatedAt == null) {
            throw new IllegalArgumentException("createdAt/expiresAt/updatedAt required");
        }
        if (!createdAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("createdAt must be before expiresAt");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    public boolean isExpired(Instant now) {
        return status == InventoryReservationStatus.RESERVED && !expiresAt.isAfter(now);
    }
}
