package com.tazzzo.delivery;

/** A slot could not be reserved for an order. {@code reason} is the only detail; no capacity figure is carried. */
public class SlotRefusedException extends RuntimeException {

    public enum Reason {
        /** The id is not {@code <window>~<yyyy-MM-dd>}. */
        INVALID,
        /** The address PIN has no active service area, so there are no slots for it. */
        NOT_SERVICEABLE,
        /** Open occurrence with no capacity left. */
        FULL,
        /** Unknown, inactive, not running that day, outside the horizon or past its cutoff. */
        UNAVAILABLE
    }

    private final Reason reason;

    public SlotRefusedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
