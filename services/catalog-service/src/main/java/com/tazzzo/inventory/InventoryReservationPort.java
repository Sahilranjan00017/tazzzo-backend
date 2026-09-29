package com.tazzzo.inventory;

import com.mongodb.client.ClientSession;

import java.time.Instant;

/**
 * PR-14A — the narrow, session-aware seam a future {@code customer.order} composes reservation
 * lifecycle operations through, inside ITS OWN outer transaction:
 *
 * <pre>
 * tx.call(session -&gt; {
 *     InventoryReservation r = reservationPort.reserve(session, command, now);
 *     orderRepository.insert(session, order.withReservationId(r.reservationId()));
 *     return order;
 * });
 * </pre>
 *
 * <p><b>CRITICAL — every method here participates in the CALLER's transaction and MUST NEVER
 * start its own ({@code Tx.call}/{@code Tx.run}).</b> The caller owns the outer commit/rollback;
 * these methods only ever touch Inventory-owned collections ({@code inventory},
 * {@code inventory_reservations}) inside the session they are given. A standalone (non-session)
 * caller uses {@link InventoryReservationService}'s own wrapper methods instead, which DO own
 * their own {@code Tx.call}.
 *
 * <p>Order must never edit the {@code inventory} collection or the {@code inventory_reservations}
 * header directly — only through this port. Inventory, in turn, knows nothing about
 * {@code customer.order}/{@code customer.payment}/{@code customer.checkout} (enforced by
 * {@code ModuleBoundaryTest}).
 */
public interface InventoryReservationPort {

    /**
     * All-or-nothing multi-SKU reservation. Idempotent by {@code command.orderId()}: a call for an
     * order that already has a durable reservation with the SAME semantic fingerprint returns that
     * reservation unchanged (no re-reservation, no double increment); a DIFFERENT fingerprint for
     * the same order throws {@link InventoryReservationFailure} with
     * {@link InventoryReservationFailure.Reason#ALREADY_RESERVED_DIFFERENT_INPUT}.
     */
    InventoryReservation reserve(ClientSession session, InventoryReservationCommand command, Instant now);

    /**
     * Releases a {@code RESERVED} reservation (un-reserves every line). Idempotent: already
     * {@code RELEASED} returns the current reservation unchanged; {@code CONSUMED} throws
     * {@link InventoryReservationFailure.Reason#INVALID_TRANSITION}.
     */
    InventoryReservation release(ClientSession session, InventoryReservationId reservationId, Instant now);

    /**
     * Consumes a {@code RESERVED} reservation (decrements {@code on_hand} AND {@code reserved} for
     * every line — the point physical stock actually leaves). Idempotent: already {@code CONSUMED}
     * returns the current reservation unchanged; {@code RELEASED} throws
     * {@link InventoryReservationFailure.Reason#INVALID_TRANSITION}.
     */
    InventoryReservation consume(ClientSession session, InventoryReservationId reservationId, Instant now);
}
