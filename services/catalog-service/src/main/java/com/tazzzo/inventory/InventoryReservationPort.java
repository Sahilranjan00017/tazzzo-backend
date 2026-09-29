package com.tazzzo.inventory;

import com.mongodb.client.ClientSession;

/**
 * PR-14A/PR-14B — the narrow, session-aware seam a future {@code customer.order} composes
 * reservation lifecycle operations through, inside ITS OWN outer transaction:
 *
 * <pre>
 * PreparedInventoryReservation prepared = reservationPort.prepare(orderId); // BEFORE the transaction
 * tx.call(session -&gt; {
 *     InventoryReservationAllocation allocation = new InventoryReservationAllocation(
 *             currentRoute.fulfillmentLocationId(), quoteItems); // constructed INSIDE the callback
 *     InventoryReservation r = reservationPort.reserve(session, prepared, allocation);
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
 * <p><b>PR-14A hardening — Inventory owns reservation identity and TTL.</b> {@link #prepare} is the
 * ONLY way to obtain a {@link PreparedInventoryReservation} (that type's constructor is
 * package-private, though the type itself is public and freely usable across packages): it
 * generates the opaque reservation id and computes {@code expiresAt} from
 * {@link InventoryReservationProperties}' configured TTL — a caller can never supply or override a
 * reservation's lifetime. {@code prepare} itself does NOT touch Mongo and is called BEFORE any
 * transaction, so its output is retry-stable across whatever transaction the caller later runs it
 * through.
 *
 * <p><b>PR-14B — preparation carries NO routing/items.</b> {@link #prepare} takes only an
 * {@code orderId}: it fixes Inventory-owned identity/lifetime and nothing else, so a caller never
 * needs to resolve mutable routing before entering its own transaction. The caller-supplied
 * "what/where" — {@link InventoryReservationAllocation} — is constructed separately, and may be
 * constructed freshly on every {@code reserve} call (including inside a transaction retry), from
 * whatever route/items are CURRENTLY valid at that moment; see that type's own javadoc for why this
 * is safe. {@link #reserve} separately re-checks {@code prepared.expiresAt()} against Inventory's
 * OWN LIVE clock at the moment it actually runs (never trusting {@code preparedAt}), because time
 * may have passed between {@code prepare} and {@code reserve} — expiry is runtime-authoritative,
 * never merely "whatever the reconciliation worker hasn't gotten to yet".
 *
 * <p><b>PR-14A hardening (clock authority) — Inventory owns "now" for release/consume too.</b>
 * Neither {@link #release} nor {@link #consume} accepts a caller-supplied {@code Instant}: both
 * read {@code clock.instant()} internally, fresh on EVERY invocation (including a
 * {@code Tx.call} driver retry), and use that SAME value for the runtime expiry check, every
 * inventory row's {@code updated_at}, and the reservation header's own {@code updatedAt}/status
 * transition. A caller supplying its own (possibly stale) "now" could otherwise pass an
 * {@code Instant} that predates {@code expiresAt} even though Inventory's real clock has already
 * moved past it — silently letting an expired reservation be consumed. Reading the clock fresh on
 * every attempt is deliberately different from {@code prepare}'s fixed-before-the-transaction
 * values: if a retry crosses {@code expiresAt} mid-flight, the LATER attempt must see the LATER
 * time and correctly fail {@code RESERVATION_EXPIRED}, aborting the whole transaction.
 *
 * <p>Order must never edit the {@code inventory} collection or the {@code inventory_reservations}
 * header directly — only through this port. Inventory, in turn, knows nothing about
 * {@code customer.order}/{@code customer.payment}/{@code customer.checkout} (enforced by
 * {@code ModuleBoundaryTest}).
 */
public interface InventoryReservationPort {

    /** Validates {@code orderId}, generates a fresh reservation id, and fixes {@code expiresAt}
     *  using the CONFIGURED TTL. No Mongo access; safe to call any number of times before a
     *  transaction — each call mints a genuinely new id, so a caller must call this ONCE per
     *  logical reserve attempt and reuse the SAME returned value across any transaction retry. */
    PreparedInventoryReservation prepare(String orderId);

    /**
     * All-or-nothing multi-SKU reservation of {@code allocation} against the identity/lifetime
     * fixed by {@link #prepare}. Idempotent by {@code orderId}: a call for an order that already
     * has a durable reservation with the SAME semantic fingerprint (derived from {@code orderId} +
     * {@code allocation}) returns that reservation unchanged (no re-reservation, no double
     * increment); a DIFFERENT fingerprint for the same order throws
     * {@link InventoryReservationFailure} with
     * {@link InventoryReservationFailure.Reason#ALREADY_RESERVED_DIFFERENT_INPUT}.
     */
    InventoryReservation reserve(ClientSession session, PreparedInventoryReservation prepared,
                                 InventoryReservationAllocation allocation);

    /**
     * Releases a {@code RESERVED} reservation (un-reserves every line). Idempotent: already
     * {@code RELEASED} returns the current reservation unchanged; {@code CONSUMED} throws
     * {@link InventoryReservationFailure.Reason#INVALID_TRANSITION}.
     */
    InventoryReservation release(ClientSession session, InventoryReservationId reservationId);

    /**
     * Consumes a {@code RESERVED} reservation (decrements {@code on_hand} AND {@code reserved} for
     * every line — the point physical stock actually leaves). Idempotent: already {@code CONSUMED}
     * returns the current reservation unchanged; {@code RELEASED} throws
     * {@link InventoryReservationFailure.Reason#INVALID_TRANSITION}. Rejects a {@code RESERVED} but
     * already-expired reservation with {@link InventoryReservationFailure.Reason#RESERVATION_EXPIRED}
     * — checked against Inventory's OWN live clock, never a caller-supplied value.
     */
    InventoryReservation consume(ClientSession session, InventoryReservationId reservationId);
}
