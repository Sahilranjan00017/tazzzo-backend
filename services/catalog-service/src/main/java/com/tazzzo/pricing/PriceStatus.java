package com.tazzzo.pricing;

/**
 * Usability of a canonical current price at a given instant.
 * Distinguishes the states a Commerce Read caller must react to differently.
 */
public enum PriceStatus {
    /** A valid, active, in-window price exists. */
    ACTIVE,
    /** No price_current row exists for this SKU/currency. */
    MISSING,
    /** A row exists but active == false. */
    INACTIVE,
    /** now < effectiveFrom. */
    NOT_YET_EFFECTIVE,
    /** now >= effectiveTo. */
    EXPIRED
}
