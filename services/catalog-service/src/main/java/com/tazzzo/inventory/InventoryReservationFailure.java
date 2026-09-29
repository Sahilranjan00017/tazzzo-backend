package com.tazzzo.inventory;

/**
 * PR-14A — every way an inventory reservation request can fail, as one typed exception carrying a
 * closed {@link Reason}. This is an INTERNAL domain outcome — no customer HTTP endpoint exists in
 * this PR — so messages may be as specific as useful; nothing here is a public error contract.
 */
public final class InventoryReservationFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST, ALREADY_RESERVED_DIFFERENT_INPUT, RESERVATION_UNAVAILABLE, NOT_FOUND,
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
