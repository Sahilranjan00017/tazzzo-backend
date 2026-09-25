package com.tazzzo.pricing;

import com.tazzzo.common.money.Currency;

import java.time.Instant;

/**
 * Internal write command for the canonical current price (STEP 7). Explicit paise inputs only —
 * no rupees, no float, no conversion. {@code expectedVersion} drives optimistic concurrency:
 * null = create (version becomes 1); a value = update only if current version matches, producing
 * version+1. {@code effectiveFrom}/{@code effectiveTo} are optional (null = immediate/open-ended).
 * Validation is performed by {@link PricingService}; invalid input becomes {@link InvalidPriceException}.
 */
public record UpsertPriceCommand(
        String skuId,
        long sellingPricePaise,
        long mrpPaise,
        Currency currency,
        Instant effectiveFrom,
        Instant effectiveTo,
        String source,
        Long expectedVersion
) { }
