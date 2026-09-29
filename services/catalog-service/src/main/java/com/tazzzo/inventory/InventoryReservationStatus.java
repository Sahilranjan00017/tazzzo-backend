package com.tazzzo.inventory;

/**
 * PR-14A — the closed reservation lifecycle. No speculative statuses: {@code RESERVED} is the one
 * non-terminal state; {@code RELEASED} and {@code CONSUMED} are both terminal and mutually
 * exclusive (a header transitions to at most one of them, never both, never back to
 * {@code RESERVED}).
 */
public enum InventoryReservationStatus {
    RESERVED, RELEASED, CONSUMED
}
