package com.tazzzo.customer.cart;

/**
 * PR-12C — the CLOSED, machine-readable reasons a cart line is not currently purchasable as
 * requested. Derived fresh on every response from current commerce truth; never persisted, never a
 * free-form string, never carrying routing detail. A line may carry several.
 */
public enum CartIssue {
    /** SKU is no longer customer-visible (unknown/hidden/ineligible). Intent is retained. */
    PRODUCT_UNAVAILABLE,
    /** No usable current price. */
    PRICE_UNAVAILABLE,
    /** No delivery location supplied, so stock/serviceability cannot be determined. */
    LOCATION_REQUIRED,
    /** The supplied location is definitely outside coverage. */
    UNSERVICEABLE,
    /** Definitely out of stock at the resolved location. */
    OUT_OF_STOCK,
    /** In stock, but the requested quantity exceeds what can currently be purchased. */
    INSUFFICIENT_STOCK,
    /** Serviceable location but stock could not be determined (e.g. no inventory row). */
    STOCK_UNKNOWN,
    /** Commerce state could not currently be read at all; nothing is asserted about the line. */
    ENRICHMENT_UNAVAILABLE
}
