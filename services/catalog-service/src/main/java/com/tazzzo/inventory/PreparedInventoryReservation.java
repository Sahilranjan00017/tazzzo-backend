package com.tazzzo.inventory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PR-14A hardening (M2) — the PUBLIC, opaque, prepared reservation command: identity
 * ({@code reservationId}) and lifetime ({@code preparedAt}/{@code expiresAt}) are Inventory's own
 * policy, computed ONLY by {@link InventoryReservationPort#prepare} from an
 * {@link InventoryReservationRequest} — never accepted from a caller and never overridable by one.
 *
 * <p><b>PUBLIC type, PACKAGE-PRIVATE constructor.</b> A caller in another package — a future
 * {@code customer.order} — receives a normal, nameable, usable value from {@code prepare(...)} and
 * passes it into {@code reserve(session, prepared)} like any ordinary Java value (unlike an earlier
 * revision of this type, which was itself package-private and relied on callers never writing its
 * name via {@code var} — workable, but not a clean cross-package contract). What it CANNOT do is
 * write {@code new PreparedInventoryReservation(...)} — a plain class's constructor, unlike a
 * record's canonical constructor, may be more restricted than the class itself, so this compiles
 * fine as {@code public final class} with a package-private constructor. This is what makes "a
 * future Order cannot invent an arbitrary reservation identity or lifetime" a compiler-enforced
 * fact, not a convention.
 *
 * <p>{@code preparedAt}/{@code reservationId}/{@code expiresAt} are fixed HERE, before any
 * transaction begins, so a driver retry of the SAME logical {@code reserve} call (the {@link Tx}
 * multiple-invocation contract this whole module already follows) reuses the identical values.
 * {@code preparedAt} is HISTORICAL PROVENANCE only (when this was prepared) — it is never used to
 * decide whether the prepared allocation is still valid; {@link InventoryReservationService#reserve}
 * re-checks {@code expiresAt} against Inventory's own LIVE injected {@code Clock} at the moment it
 * actually runs, precisely because time may have passed between {@code prepare} and {@code reserve}.
 */
public final class PreparedInventoryReservation {

    static final int MAX_DISTINCT_ITEMS = 50;

    private final String orderId;
    private final String fulfillmentLocationId;
    private final List<InventoryReservationItem> items;
    private final InventoryReservationId reservationId;
    private final Instant preparedAt;
    private final Instant expiresAt;

    PreparedInventoryReservation(String orderId, String fulfillmentLocationId,
                                 List<InventoryReservationItem> items, InventoryReservationId reservationId,
                                 Instant preparedAt, Instant expiresAt) {
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
        this.orderId = orderId;
        this.fulfillmentLocationId = fulfillmentLocationId;
        this.items = List.copyOf(items);
        this.reservationId = reservationId;
        this.preparedAt = preparedAt;
        this.expiresAt = expiresAt;
    }

    public String orderId() {
        return orderId;
    }

    public String fulfillmentLocationId() {
        return fulfillmentLocationId;
    }

    public List<InventoryReservationItem> items() {
        return items;
    }

    public InventoryReservationId reservationId() {
        return reservationId;
    }

    public Instant preparedAt() {
        return preparedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
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
