package com.tazzzo.customer.order;

/**
 * PR-14B — Order Foundation ships exactly ONE reachable status. No speculative values: an Order
 * created by this PR is always {@code CREATED} (paired 1:1 with a {@code RESERVED} Inventory
 * reservation, never inserted otherwise). A future PR-15A introduces {@code CONFIRMED} — reachable
 * only when a payment condition is satisfied AND the reservation transitions to {@code CONSUMED}
 * atomically with the Order status change (see {@code OrderService}'s class-level documentation) —
 * and {@code CANCELLED}. Adding a status this PR can never produce would be exactly the kind of
 * defensive-but-dead surface this codebase's own conventions avoid elsewhere.
 */
public enum OrderStatus {
    CREATED
}
