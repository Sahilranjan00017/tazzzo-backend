package com.tazzzo.customer.cart;

/**
 * PR-15A-0 — the two NORMAL results of {@link CartPurchasePort#finalizePurchase}. Neither is a
 * failure: a newer cart belongs to the customer and is deliberately never destroyed.
 */
public enum CartPurchaseOutcome {
    /** The live cart was still the purchased source version: cleared, version advanced. */
    CLEARED,
    /** The live cart had already moved past the purchased version: left untouched. */
    NEWER_CART_PRESERVED
}
