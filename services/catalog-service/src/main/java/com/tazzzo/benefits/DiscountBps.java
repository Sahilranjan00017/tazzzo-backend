package com.tazzzo.benefits;

import com.tazzzo.common.money.Money;

/**
 * An exact percentage in BASIS POINTS: {@code 10000 bps = 100%}, {@code 500 bps = 5%}. Integer only — there is
 * no floating point and no {@code BigDecimal} anywhere in a Benefits calculation.
 *
 * <p>{@link #applyTo} is the one rounding rule of the Benefits domain:
 * {@code floor(subtotalPaise * bps / 10000)}. It is computed as {@code q*bps + (r*bps)/10000} with
 * {@code q = paise / 10000}, {@code r = paise % 10000}, which is mathematically identical to the floor of the
 * product but can never overflow a {@code long} (the result is at most {@code paise}, and {@code r*bps < 10^8}).
 */
public record DiscountBps(int bps) {

    public static final int FULL = 10_000;

    public DiscountBps {
        if (bps < 0 || bps > FULL) {
            throw new IllegalArgumentException("discount bps must be within 0.." + FULL + ": " + bps);
        }
    }

    /** {@code floor(subtotal * bps / 10000)}, in the subtotal's currency; never more than the subtotal. */
    public Money applyTo(Money subtotal) {
        if (subtotal == null) {
            throw new IllegalArgumentException("subtotal required");
        }
        long paise = subtotal.paise();
        long discount = Math.addExact(Math.multiplyExact(paise / FULL, (long) bps), (paise % FULL) * bps / FULL);
        return new Money(discount, subtotal.currency());
    }
}
