package com.tazzzo.delivery;

/** Outcome of reserving capacity for a hold. */
public enum SlotReservation {
    /** Capacity was taken for this hold. */
    RESERVED,
    /** This hold already owns a unit (a retry): nothing changed. */
    ALREADY_HELD,
    /** The occurrence is open but has no capacity left. */
    FULL,
    /** Unknown, inactive, not running that day, outside the horizon, or past its cutoff. */
    UNAVAILABLE
}
