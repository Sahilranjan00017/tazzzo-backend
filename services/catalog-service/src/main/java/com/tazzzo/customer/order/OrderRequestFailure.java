package com.tazzzo.customer.order;

/**
 * PR-15A-2 — a request the HTTP boundary rejects BEFORE any domain call (malformed body, unsupported
 * payment method). Deliberately separate from {@link OrderFailure}, which is the domain's own
 * vocabulary: these never reach {@code OrderService}, so no domain metric ever sees them.
 */
final class OrderRequestFailure extends RuntimeException {

    enum Reason { INVALID_REQUEST, PAYMENT_METHOD_UNSUPPORTED }

    private final Reason reason;

    OrderRequestFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
