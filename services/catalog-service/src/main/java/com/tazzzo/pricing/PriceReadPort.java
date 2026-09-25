package com.tazzzo.pricing;

/**
 * Internal read boundary for canonical current price (STEP 11). Returns a pricing-domain
 * {@link PriceLookup}, never an API DTO. A future Commerce Read module depends on THIS port;
 * this module never depends on commerce.read/commerce.api. No /v1 controller exists in PR-03.
 */
public interface PriceReadPort {

    /** Resolve the current price for a SKU (INR), evaluated against the service clock. */
    PriceLookup findCurrentPrice(String skuId);
}
