package com.tazzzo.customer.order;

/**
 * PR-14B — every way {@code OrderService.createOrder}/{@code placeCodOrder} can fail, as one typed exception carrying a
 * closed {@link Reason}. This is an INTERNAL domain outcome — no customer HTTP endpoint exists in
 * this PR — so messages may be as specific as useful; nothing here is a public error contract.
 *
 * <p>Deliberately excludes {@code SERVICEABILITY_CHANGED}: the final design resolves routing with
 * exactly ONE authoritative read, taken INSIDE the transaction, so there is no stale pre-transaction
 * route to compare against. Also excludes {@code ALREADY_EXISTS_DIFFERENT_INPUT}: unlike Inventory
 * (which has a real allocation fingerprint an idempotent replay can conflict with),
 * {@code (customerId, quoteId)} has no second semantic dimension to conflict on — a quote is
 * immutable once created, so replaying order-creation for the same quote always means the same
 * order. An unreachable reason kept merely "defensively" is exactly the dead surface this
 * codebase's own conventions avoid.
 */
public final class OrderFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST, QUOTE_NOT_FOUND, QUOTE_EXPIRED, ADDRESS_CHANGED, NOT_SERVICEABLE, PRICE_CHANGED,
        PRODUCT_UNAVAILABLE, STOCK_UNAVAILABLE, RESERVATION_EXPIRED, INTEGRITY_FAILURE, UNAVAILABLE,
        /** PR-15A-1 — a FRESH placement whose source cart version is already covered by
         *  {@code purchasedThroughVersion}: a DIFFERENT quote from the same (or an older) cart already
         *  produced an Order. Not an idempotency conflict — this quote has no Order; its source cart
         *  intent was consumed by another. Never raised for a same-quote replay (the durable Order
         *  lookup returns first). */
        CART_VERSION_ALREADY_PURCHASED,
        /** PR-15A-2 — READ path only ({@code OrderService.getOrder}): the id is malformed, unknown,
         *  owned by another customer, or names an internal non-{@code CONFIRMED} row. All four are one
         *  indistinguishable outcome by design. The placement path never raises it. */
        ORDER_NOT_FOUND,
        /** The requested delivery slot is full, closed or not offered for the address (409; nothing was written). */
        SLOT_UNAVAILABLE,
        /** Cancellation: the order is not in a state a customer may cancel from (409). */
        NOT_CANCELLABLE,
        /** Cancellation: the customer cancellation window is closed, or no window is configured (409). */
        CANCELLATION_WINDOW_CLOSED,
        /** Staff: the version the caller saw is no longer current (409). */
        STALE_VERSION,
        /** Staff: the order's current status does not allow that transition (409). */
        INVALID_TRANSITION
    }

    private final Reason reason;

    public OrderFailure(Reason reason) {
        this(reason, reason.name());
    }

    public OrderFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
