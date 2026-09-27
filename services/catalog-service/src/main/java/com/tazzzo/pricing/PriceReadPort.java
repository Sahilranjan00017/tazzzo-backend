package com.tazzzo.pricing;

import com.tazzzo.common.money.Currency;

/**
 * Internal read boundary for canonical current price (STEP 11). Returns a pricing-domain
 * {@link PriceLookup}, never an API DTO. A future Commerce Read module depends on THIS port;
 * this module never depends on commerce.read/commerce.api. No /v1 controller exists in PR-03.
 */
public interface PriceReadPort {

    /** Resolve the current price for a SKU (INR), evaluated against the service clock. */
    PriceLookup findCurrentPrice(String skuId);

    /**
     * Batch resolve current prices for a page of SKUs at one currency (PR-10A) — the foundation
     * for the future public list overlay so a category page never issues N point reads. Every
     * requested SKU is present in the result: a SKU with no row maps to
     * {@link PriceLookup#missing()}; each present row carries its evaluated {@link PriceStatus}
     * ({@code ACTIVE}/{@code INACTIVE}/{@code EXPIRED}/{@code NOT_YET_EFFECTIVE}) so callers never
     * confuse "not returned" with "no usable price". Duplicate input ids are deduplicated. The
     * result is location-independent — price is never keyed by location.
     *
     * <p>This default is the naive per-SKU fallback so functional-interface test stubs keep
     * working; {@link PricingService} overrides it with ONE indexed query.
     */
    default java.util.Map<String, PriceLookup> findCurrentPrices(
            java.util.Collection<String> skuIds, Currency currency) {
        java.util.Objects.requireNonNull(skuIds, "skuIds required");
        java.util.Objects.requireNonNull(currency, "currency required");
        java.util.Map<String, PriceLookup> out = new java.util.LinkedHashMap<>();
        for (String skuId : skuIds) {
            // NOT putIfAbsent(skuId, findCurrentPrice(...)): arguments evaluate eagerly, so that
            // shape would re-read duplicates. Guard first — duplicates cost exactly one read each.
            if (!out.containsKey(skuId)) {
                out.put(skuId, findCurrentPrice(skuId));
            }
        }
        return out;
    }
}
