package com.tazzzo.customer.cart;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;

/**
 * PR-15A-0 — the narrow, session-aware seam a future {@code customer.order} composes inside ITS OWN
 * outer transaction to keep a purchased cart from producing a second Order.
 *
 * <p><b>CRITICAL — every method participates in the CALLER's transaction and MUST NEVER start its
 * own ({@code Tx.call}/{@code Tx.run}), record metrics, or be treated as durable until the caller's
 * transaction commits.</b> The caller owns commit/rollback: a rolled-back caller leaves both the
 * cart contents AND {@code purchasedThroughVersion} exactly as they were. {@code customer.cart}
 * knows nothing about {@code customer.order} (enforced by {@code ModuleBoundaryTest}); this port
 * speaks only in cart vocabulary — a customer, and the cart version a checkout quote was taken from.
 *
 * <p><b>{@code purchasedThroughVersion}</b> is the highest cart version whose checkout intent has
 * already produced a committed purchase. It is monotonic ({@code $max}, never lowered, never reset,
 * the cart document is never deleted) and an absent field means 0. Multiple checkout quotes can
 * exist for the same cart version (quote uniqueness is per idempotency key, not per cart version),
 * so this marker — not the quote — is what stops two different quotes from one cart both placing.
 *
 * <p><b>Intended call order for a FRESH placement</b> (the caller's own durable replay check for an
 * already-placed quote comes FIRST and never reaches this port, so replay is never rejected by the
 * marker):
 * <ol>
 *   <li>{@link #isSourceVersionPurchased} — reject a fresh placement whose source cart version is
 *       already covered;</li>
 *   <li>...the caller's own validation, reserve, consume and Order insert...</li>
 *   <li>{@link #finalizePurchase} — in the SAME transaction.</li>
 * </ol>
 */
public interface CartPurchasePort {

    /**
     * @return true iff {@code purchasedThroughVersion >= sourceCartVersion}. A missing cart
     *         document, or a cart that has never been purchased from, is {@code false}. Read-only.
     */
    boolean isSourceVersionPurchased(ClientSession session, CustomerId customerId, long sourceCartVersion);

    /**
     * Records that {@code sourceCartVersion} has been purchased and, ONLY if the live cart is still
     * exactly that version, clears it (advancing the version, never deleting the document).
     * <ul>
     *   <li>live version == source: items cleared, version advanced by one, marker raised to at least
     *       {@code sourceCartVersion} — {@link CartPurchaseOutcome#CLEARED};</li>
     *   <li>live version &gt; source: the customer has since edited the cart. Items and version are
     *       left UNTOUCHED; only the marker is raised — {@link CartPurchaseOutcome#NEWER_CART_PRESERVED}.
     *       This is a normal result, never a failure;</li>
     *   <li>anything impossible for a real quote (no cart document, or a live version BELOW the
     *       source version) fails loud with {@link CartPurchaseIntegrityException}, aborting the
     *       caller's transaction.</li>
     * </ul>
     * Idempotent under a transaction-callback retry: {@code $max} cannot lower the marker, and an
     * aborted attempt commits nothing.
     */
    CartPurchaseOutcome finalizePurchase(ClientSession session, CustomerId customerId, long sourceCartVersion);
}
