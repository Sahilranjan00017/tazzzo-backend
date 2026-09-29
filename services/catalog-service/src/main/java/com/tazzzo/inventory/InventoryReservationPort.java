package com.tazzzo.inventory;

import com.mongodb.client.ClientSession;

import java.time.Instant;

/**
 * PR-14A — the narrow, session-aware seam a future {@code customer.order} composes reservation
 * lifecycle operations through, inside ITS OWN outer transaction:
 *
 * <pre>
 * var prepared = reservationPort.prepare(request);   // BEFORE the transaction — fixes identity/TTL
 * tx.call(session -&gt; {
 *     InventoryReservation r = reservationPort.reserve(session, prepared);
 *     orderRepository.insert(session, order.withReservationId(r.reservationId()));
 *     return order;
 * });
 * </pre>
 *
 * <p><b>CRITICAL — every session-aware method here participates in the CALLER's transaction and
 * MUST NEVER start its own ({@code Tx.call}/{@code Tx.run}).</b> The caller owns the outer
 * commit/rollback; these methods only ever touch Inventory-owned collections ({@code inventory},
 * {@code inventory_reservations}) inside the session they are given. A standalone (non-session)
 * caller uses {@link InventoryReservationService}'s own wrapper methods instead, which DO own
 * their own {@code Tx.call}.
 *
 * <p><b>PR-14A hardening (M1) — Inventory owns reservation identity and TTL.</b> {@link #prepare}
 * is the ONLY way to obtain an {@code InventoryReservationCommand} (that type's canonical
 * constructor is package-private): it validates the request, generates the opaque reservation id,
 * and computes {@code expiresAt} from {@link InventoryReservationProperties}' configured TTL — a
 * caller can never supply or override a reservation's lifetime. {@code prepare} itself does NOT
 * touch Mongo and is called BEFORE any transaction, so its output is retry-stable across whatever
 * transaction the caller later runs it through.
 *
 * <p>Order must never edit the {@code inventory} collection or the {@code inventory_reservations}
 * header directly — only through this port. Inventory, in turn, knows nothing about
 * {@code customer.order}/{@code customer.payment}/{@code customer.checkout} (enforced by
 * {@code ModuleBoundaryTest}).
 */
public interface InventoryReservationPort {

    /** Validates {@code request}, generates a fresh reservation id, and fixes {@code expiresAt}
     *  using the CONFIGURED TTL. No Mongo access; safe to call any number of times before a
     *  transaction — each call mints a genuinely new id, so a caller must call this ONCE per
     *  logical reserve attempt and reuse the SAME returned value across any transaction retry. */
    InventoryReservationCommand prepare(InventoryReservationRequest request);

    /**
     * All-or-nothing multi-SKU reservation of a command from {@link #prepare}. Idempotent by
     * {@code orderId}: a call for an order that already has a durable reservation with the SAME
     * semantic fingerprint returns that reservation unchanged (no re-reservation, no double
     * increment); a DIFFERENT fingerprint for the same order throws
     * {@link InventoryReservationFailure} with
     * {@link InventoryReservationFailure.Reason#ALREADY_RESERVED_DIFFERENT_INPUT}.
     */
    InventoryReservation reserve(ClientSession session, InventoryReservationCommand prepared);

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
