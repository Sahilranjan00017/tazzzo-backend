package com.tazzzo.customer.checkout;

/**
 * PR-13A — the CLOSED reasons a cart line blocks a checkout quote. Derived from current commerce
 * truth (the same runtime cards the cart shows); never free text, never carrying routing detail.
 */
public enum CheckoutItemReason {
    PRODUCT_UNAVAILABLE,
    PRICE_UNAVAILABLE,
    OUT_OF_STOCK,
    INSUFFICIENT_STOCK,
    STOCK_UNKNOWN,
    /** Not buyable for a reason no more specific closed code covers (fails closed). */
    NOT_BUYABLE
}
