package com.tazzzo.customer.cart;

/**
 * PR-15A-0 — a cart state that cannot exist for a real purchased quote (no cart document, a live
 * version below the purchased source version, or a corrupt marker). Thrown from inside the caller's
 * transaction so the WHOLE placement aborts; never a customer-facing outcome, never a normal
 * version-mismatch (that is {@link CartPurchaseOutcome#NEWER_CART_PRESERVED}).
 */
public final class CartPurchaseIntegrityException extends RuntimeException {

    public CartPurchaseIntegrityException(String message) {
        super(message);
    }
}
