package com.tazzzo.inventory;

import java.time.Instant;

/**
 * PR-14B (preparation-contract evolution) — the PUBLIC, opaque, prepared reservation identity:
 * Inventory-owned identity ({@code reservationId}) and lifetime ({@code preparedAt}/
 * {@code expiresAt}), computed ONLY by {@link InventoryReservationPort#prepare} from an
 * {@code orderId} — never accepted from a caller and never overridable by one.
 *
 * <p><b>PR-14B — no location, no items.</b> An earlier revision of this type also carried
 * {@code fulfillmentLocationId}/{@code items}, which forced a caller (a future {@code customer.order})
 * to resolve mutable routing BEFORE calling {@code prepare} — and therefore before its own outer
 * transaction — reopening exactly the mutable-preflight race Order's idempotency design forbids
 * (an existing committed Order must win over EVERY later mutable authority, with no exception for
 * "the routing read that happened before the transaction started"). Splitting the caller-supplied,
 * semantic "what/where" into {@link InventoryReservationAllocation} — constructed fresh, INSIDE the
 * caller's transaction callback, from whatever route/items are CURRENTLY valid — removes that
 * requirement entirely: only Inventory's own identity/lifetime need exist before the transaction.
 *
 * <p><b>PUBLIC type, PACKAGE-PRIVATE constructor.</b> A caller in another package — a future
 * {@code customer.order} — receives a normal, nameable, usable value from {@code prepare(...)} and
 * passes it into {@code reserve(session, prepared, allocation)} like any ordinary Java value. What
 * it CANNOT do is write {@code new PreparedInventoryReservation(...)} — a plain class's constructor,
 * unlike a record's canonical constructor, may be more restricted than the class itself, so this
 * compiles fine as {@code public final class} with a package-private constructor. This is what
 * makes "a future Order cannot invent an arbitrary reservation identity or lifetime" a
 * compiler-enforced fact, not a convention.
 *
 * <p>{@code preparedAt}/{@code reservationId}/{@code expiresAt} are fixed HERE, before any
 * transaction begins, so a driver retry of the SAME logical {@code reserve} call (the {@link Tx}
 * multiple-invocation contract this whole module already follows) reuses the identical values —
 * even though the {@link InventoryReservationAllocation} passed alongside them on each retry
 * attempt may legitimately differ (see that type's own javadoc). {@code preparedAt} is HISTORICAL
 * PROVENANCE only (when this was prepared) — it is never used to decide whether the prepared
 * allocation is still valid; {@link InventoryReservationService#reserve} re-checks
 * {@code expiresAt} against Inventory's own LIVE injected {@code Clock} at the moment it actually
 * runs, precisely because time may have passed between {@code prepare} and {@code reserve}.
 */
public final class PreparedInventoryReservation {

    static final int MAX_DISTINCT_ITEMS = 50;

    private final InventoryReservationId reservationId;
    private final String orderId;
    private final Instant preparedAt;
    private final Instant expiresAt;

    PreparedInventoryReservation(InventoryReservationId reservationId, String orderId, Instant preparedAt,
                                 Instant expiresAt) {
        if (reservationId == null) {
            throw new IllegalArgumentException("reservationId required");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId required");
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
        this.reservationId = reservationId;
        this.orderId = orderId;
        this.preparedAt = preparedAt;
        this.expiresAt = expiresAt;
    }

    public InventoryReservationId reservationId() {
        return reservationId;
    }

    public String orderId() {
        return orderId;
    }

    public Instant preparedAt() {
        return preparedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
