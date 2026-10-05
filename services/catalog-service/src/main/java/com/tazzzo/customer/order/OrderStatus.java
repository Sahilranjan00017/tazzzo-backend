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
 *   <li>{@code OUT_FOR_DELIVERY} (version 3) -- staff ({@code order-ops}) handed a CONFIRMED order to delivery.</li>
 *   <li>{@code DELIVERED} (version 4) -- staff recorded the hand-over to the customer. Terminal. (Cash collection on COD is a
 *       payment concern and is NOT recorded here.)</li>
 * </ul>
 * {@code CANCELLED} is version 3 from CONFIRMED and version 4 from OUT_FOR_DELIVERY (a failed or refused delivery); a
 * DELIVERED order is never cancelled (that would be a return, which is not modelled).
 */
public enum OrderStatus {
    CREATED, CONFIRMED, CANCELLED, OUT_FOR_DELIVERY, DELIVERED
}
