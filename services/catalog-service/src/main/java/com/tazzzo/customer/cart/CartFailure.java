package com.tazzzo.customer.cart;

/**
 * PR-12C — every way a cart request can fail for reasons OWNED by this domain, as one typed
 * exception carrying a bounded, closed {@link Reason}. Authentication stays CustomerAuthFilter's
 * concern (401). {@code NOT_FOUND} deliberately covers unknown SKU, hidden/ineligible SKU, an
 * absent cart line and a foreign/unknown addressId identically — no existence/ownership oracle.
 */
public final class CartFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST, NOT_FOUND, PRECONDITION_REQUIRED, PRECONDITION_FAILED, CART_ITEM_LIMIT_REACHED,
        UNAVAILABLE
    }

    private final Reason reason;

    public CartFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
