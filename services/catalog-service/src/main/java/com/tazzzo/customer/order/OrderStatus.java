package com.tazzzo.customer.order;

/**
 * PR-15A-1 — the Order state machine, exactly two reachable statuses, each with a real producer:
 * <ul>
 *   <li>{@code CREATED} (version 1) — produced ONLY by the internal create-only path
 *       ({@code OrderService.createOrder}, paired with a {@code RESERVED} reservation). No customer-
 *       reachable operation produces it; it exists so a future prepaid flow can compose it.</li>
 *   <li>{@code CONFIRMED} (version 2) — produced by COD placement
 *       ({@code OrderService.placeCodOrder}), born confirmed in ONE transaction alongside a
 *       {@code CONSUMED} reservation. {@code version} is the state-machine position, not a count of
 *       Mongo writes: a direct COD placement never commits {@code CREATED}, yet is still version 2 so a
 *       future {@code CREATED -> CONFIRMED} CAS ({@code 1 -> 2}) yields the same coherent model.</li>
 * </ul>
 * No {@code CANCELLED}/{@code SHIPPED}/... — nothing produces them yet.
 */
public enum OrderStatus {
    CREATED, CONFIRMED
}
