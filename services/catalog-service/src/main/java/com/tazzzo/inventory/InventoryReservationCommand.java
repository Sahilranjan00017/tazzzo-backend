package com.tazzzo.inventory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PR-14A hardening (M1) — the PREPARED, Inventory-owned reservation command: identity
 * ({@code reservationId}) and lifetime ({@code preparedAt}/{@code expiresAt}) are Inventory's own
 * policy, computed ONLY by {@link InventoryReservationPort#prepare} from an
 * {@link InventoryReservationRequest} — never accepted from a caller and never overridable by one.
 *
 * <p><b>PACKAGE-PRIVATE ON PURPOSE.</b> {@link InventoryReservationPort} is public and declares
 * {@code prepare}/{@code reserve} using this type, so a caller in another package — a future
 * {@code customer.order} — can receive one from {@code prepare(...)} and pass it straight into
 * {@code reserve(session, prepared)} (holding it via {@code var}, never by writing this type's
 * name), but CANNOT write {@code new InventoryReservationCommand(...)} itself — that line fails to
 * compile outside {@code com.tazzzo.inventory}. This is what makes "a future Order cannot invent an
 * arbitrary reservation lifetime" a compiler-enforced fact, not a convention.
 *
 * <p>{@code preparedAt}/{@code reservationId}/{@code expiresAt} are fixed HERE, before any
 * transaction begins, so a driver retry of the SAME logical {@code reserve} call (the {@link Tx}
 * multiple-invocation contract this whole module already follows) reuses the identical values.
 */
record InventoryReservationCommand(String orderId, String fulfillmentLocationId,
                                   List<InventoryReservationItem> items, InventoryReservationId reservationId,
                                   Instant preparedAt, Instant expiresAt) {

    static final int MAX_DISTINCT_ITEMS = 50;

    InventoryReservationCommand {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId required");
        }
        if (fulfillmentLocationId == null || fulfillmentLocationId.isBlank()) {
            throw new IllegalArgumentException("fulfillmentLocationId required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must be non-empty");
        }
        if (items.size() > MAX_DISTINCT_ITEMS) {
            throw new IllegalArgumentException("items exceeds the limit of " + MAX_DISTINCT_ITEMS);
        }
        Set<String> seen = new HashSet<>();
        for (InventoryReservationItem item : items) {
            if (!seen.add(item.skuId())) {
                throw new IllegalArgumentException("duplicate skuId in reservation command: " + item.skuId());
            }
        }
        items = List.copyOf(items);
        if (reservationId == null) {
            throw new IllegalArgumentException("reservationId required");
        }
        if (preparedAt == null) {
            throw new IllegalArgumentException("preparedAt required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt required");
        }
        if (!preparedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("preparedAt must be before expiresAt");
        }
    }

    /**
     * PR-14A — the canonical, order-independent fingerprint the fingerprint hash is built from:
     * items sorted by skuId, so the client's original request ordering can never change identity.
     * {@code fingerprintVersion} is a manual tag ("v1") — bump it, never overload its meaning, if a
     * future field becomes meaning-bearing (documented on {@link InventoryReservationService}).
     */
    String canonicalFingerprintInput(String fingerprintVersion) {
        StringBuilder sb = new StringBuilder(fingerprintVersion).append('|').append(orderId).append('|')
                .append(fulfillmentLocationId);
        items.stream().sorted(java.util.Comparator.comparing(InventoryReservationItem::skuId))
                .forEach(i -> sb.append('|').append(i.skuId()).append(':').append(i.quantity()));
        return sb.toString();
    }
}
