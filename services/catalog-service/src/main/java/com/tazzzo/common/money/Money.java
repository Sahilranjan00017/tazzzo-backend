package com.tazzzo.common.money;

import java.util.Objects;

/**
 * Production money primitive (ADR-002): an exact, immutable, non-negative amount in the
 * currency's minor unit (paise for INR), stored as a {@code long}. There is no floating point
 * and no BigDecimal-based wire format anywhere. This is a DOMAIN type — the public {@code /v1}
 * contract carries bare int64 paise fields (e.g. {@code sellingPricePaise}); this type is used
 * for internal validation and arithmetic, never as the JSON shape.
 *
 * <p>The type is currency-qualified ({@code Money(long paise, Currency)}) rather than a bare
 * {@code MoneyPaise(long)} so multi-currency support is a future additive change, not a rewrite.
 * Phase 1 remains simple via the {@link #ofInrPaise(long)} factory.
 *
 * <p>No implicit conversion exists from the legacy ambiguous {@code price} integer — construction
 * is explicit only, so a rupee value can never be silently treated as paise.
 */
public record Money(long paise, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency required");
        if (paise < 0) {
            throw new IllegalArgumentException("money amount must be non-negative: " + paise);
        }
    }

    /** The only Phase-1 factory. Explicit paise in, explicit currency out. */
    public static Money ofInrPaise(long paise) {
        return new Money(paise, Currency.INR);
    }

    /** Overflow-safe addition; both operands must share a currency. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.paise, other.paise), currency);
    }

    /** Overflow-safe, non-negative subtraction; both operands must share a currency. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        long result = Math.subtractExact(this.paise, other.paise);
        if (result < 0) {
            throw new IllegalArgumentException("money subtraction would be negative");
        }
        return new Money(result, currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "operand required");
        if (this.currency != other.currency) {
            throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
