package com.tazzzo.inventory;

/**
 * PR-14A — every way an inventory reservation request can fail, as one typed exception carrying a
 * closed {@link Reason}. This is an INTERNAL domain outcome — no customer HTTP endpoint exists in
 * this PR — so messages may be as specific as useful; nothing here is a public error contract.
 */
public final class InventoryReservationFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST, ALREADY_RESERVED_DIFFERENT_INPUT, RESERVATION_UNAVAILABLE, NOT_FOUND,
        /** A {@code RESERVED} allocation whose {@code expiresAt} has already passed at the moment of
         *  the check — runtime-authoritative, independent of whether the expiry-reconciliation
         *  worker has run yet. Thrown by {@code reserve} (a fresh, already-stale prepared command),
         *  by an idempotent replay that finds the durable header itself expired, and by
         *  {@code consume} (never let an expired hold be silently honoured). Never thrown by
         *  {@code release} — releasing an expired reservation is exactly the recovery path. */
        RESERVATION_EXPIRED,
        INVALID_TRANSITION, INTEGRITY_FAILURE, UNAVAILABLE
    }

    private final Reason reason;

    public InventoryReservationFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
