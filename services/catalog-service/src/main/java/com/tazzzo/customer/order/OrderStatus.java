package com.tazzzo.customer.order;

/**
 * The Order state machine. {@code version} is the state-machine position, not a count of Mongo writes.
 * <ul>
 *   <li>{@code CREATED} (version 1) -- produced ONLY by the internal create-only path
 *       ({@code OrderService.createOrder}); no customer-reachable operation produces it.</li>
 *   <li>{@code CONFIRMED} (version 2) -- produced by COD placement, born confirmed in ONE transaction alongside a
 *       {@code CONSUMED} reservation.</li>
 *   <li>{@code CANCELLED} (version 3) -- produced ONLY by {@code OrderLifecycleService.cancel} from {@code CONFIRMED}, in
 *       one transaction that also returns the stock, releases the delivery slot hold and records who cancelled and why.
 *       Terminal.</li>
 * </ul>
 * Fulfilment statuses (out for delivery, delivered) are not modelled yet: they need the staff-role model (admin RBAC).
 */
public enum OrderStatus {
    CREATED, CONFIRMED, CANCELLED
}
