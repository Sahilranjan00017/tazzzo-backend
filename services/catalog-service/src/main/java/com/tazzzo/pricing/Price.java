package com.tazzzo.pricing;

import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical current price for a SKU (the {@code price_current} SoT, ADR-003/ADR-004 sibling for
 * price). Amounts are exact int64 paise (ADR-002). Immutable value; the effective window semantics
 * (Phase 3.1 STEP 12) live here so read and tests share one definition.
 *
 * <p>{@code effectiveFrom} null means "immediate"; {@code effectiveTo} null means "open-ended".
 * A price is usable at {@code now} iff active AND effectiveFrom (if set) <= now AND now < effectiveTo
 * (if set) — from is INCLUSIVE, to is EXCLUSIVE.
 */
public record Price(
        String skuId,
        Currency currency,
        long sellingPricePaise,
        long mrpPaise,
        long version,
        boolean active,
        Instant effectiveFrom,
        Instant effectiveTo
) {
    public Price {
        Objects.requireNonNull(skuId, "skuId required");
        Objects.requireNonNull(currency, "currency required");
        // Validate amounts via the Money primitive (rejects negatives) without leaking Money to the wire.
        Money selling = new Money(sellingPricePaise, currency);
        Money mrp = new Money(mrpPaise, currency);
        if (mrp.paise() < selling.paise()) {
            throw new IllegalArgumentException("mrpPaise (" + mrpPaise + ") must be >= sellingPricePaise (" + sellingPricePaise + ")");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        if (effectiveFrom != null && effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
    }

    /** Server-derived discount in paise (never authoritative, never persisted here). */
    public long discountAmountPaise() {
        return mrpPaise - sellingPricePaise;
    }

    /** Effective-window usability (STEP 12): from inclusive, to exclusive, gated by active. */
    public boolean isUsableAt(Instant now) {
        return statusAt(now) == PriceStatus.ACTIVE;
    }

    public PriceStatus statusAt(Instant now) {
        Objects.requireNonNull(now, "now required");
        if (!active) return PriceStatus.INACTIVE;
        if (effectiveFrom != null && now.isBefore(effectiveFrom)) return PriceStatus.NOT_YET_EFFECTIVE;
        if (effectiveTo != null && !now.isBefore(effectiveTo)) return PriceStatus.EXPIRED;
        return PriceStatus.ACTIVE;
    }
}
