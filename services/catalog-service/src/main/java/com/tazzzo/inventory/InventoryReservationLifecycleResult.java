package com.tazzzo.inventory;

/**
 * PR-14A hardening (M4) — internal outcome of a {@code release}/{@code consume} attempt,
 * distinguishing a transition THIS call actually caused from an idempotent replay that found the
 * reservation already terminal. The public {@link InventoryReservationPort} and
 * {@link InventoryReservationService} standalone methods return the plain
 * {@link InventoryReservation} (a future Order only needs the current state); {@code transitioned}
 * is consumed internally — by the standalone wrappers (to decide whether to emit a
 * {@code inventory_reservation_transition} metric) and by {@link InventoryReservationExpiryWorker}
 * (to decide whether IT actually expired something, rather than merely observing a race it lost).
 */
record InventoryReservationLifecycleResult(InventoryReservation reservation, boolean transitioned) {
}
