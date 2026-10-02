package com.tazzzo.customer.checkout;

/**
 * The BINDING commerce money of a quote: the amount the customer reviews, computed once when the quote is created,
 * persisted with it and never recomputed (an idempotent replay and a GET return it unchanged). Order placement either
 * reproduces it EXACTLY (both the merchandise subtotal and the benefit discount, in either direction) or is refused with
 * {@code PAYABLE_CHANGED}; a quote that has none cannot be ordered. Same V1 formula as the Order's money snapshot,
 * deliberately a SEPARATE type (different owner; Checkout never depends on Order):
 * <pre>
 *   payablePaise = merchandiseSubtotalPaise - benefitDiscountPaise
 * </pre>
 * V1 scope (decisions, not claims that these can never exist): selling prices are tax-inclusive, so there is NO separate
 * tax/GST calculation; NO delivery, platform, handling, small-cart or other fees; NO coupons; Coins are NOT spendable;
 * NO wallet. None appears here, not even as a zero placeholder. A later component is an explicit additive field and a
 * historical quote is never recomputed.
 *
 * <p>{@code payablePaise} is DERIVED, never accepted from a caller, so an inconsistent payable cannot be constructed
 * (reconstruction verifies the stored value against the formula). {@code 0} is valid (a 100% discount). It is NOT
 * Payment authority and NOT a price lock: nothing is authorized, captured or collected. Order placement re-computes the
 * money only to VERIFY this agreement: a different result refuses the order ({@code PAYABLE_CHANGED}), and a different
 * payable is never committed.
 *
 * <p>Sources, enforced by {@link CheckoutQuote}: {@code merchandiseSubtotalPaise} is the quote's own canonical
 * {@code subtotalPaise}; {@code benefitDiscountPaise} is the STORED {@link CheckoutBenefitSnapshot} discount
 * (0 for no benefit), never recomputed from a rate and never a second Benefits call. ABSENCE on a quote means a quote
 * created before this model: it never means {@code payable = subtotal} or {@code 0}.
 */
public record CheckoutMoneySnapshot(long merchandiseSubtotalPaise, long benefitDiscountPaise) {

    public CheckoutMoneySnapshot {
        if (merchandiseSubtotalPaise < 0) {
            throw new IllegalArgumentException("merchandiseSubtotalPaise must be >= 0: " + merchandiseSubtotalPaise);
        }
        if (benefitDiscountPaise < 0) {
            throw new IllegalArgumentException("benefitDiscountPaise must be >= 0: " + benefitDiscountPaise);
        }
        if (benefitDiscountPaise > merchandiseSubtotalPaise) {
            throw new IllegalArgumentException("benefitDiscountPaise must not exceed merchandiseSubtotalPaise");
        }
    }

    /** From the quote's canonical subtotal and its ALREADY-STORED Benefits snapshot (no Benefits call). */
    static CheckoutMoneySnapshot from(long quoteSubtotalPaise, CheckoutBenefitSnapshot benefitSnapshot) {
        return new CheckoutMoneySnapshot(quoteSubtotalPaise, discountOf(benefitSnapshot));
    }

    /** The stored discount of the snapshot: {@code Applied.discountPaise}, 0 for no benefit. Never a rate recomputation. */
    static long discountOf(CheckoutBenefitSnapshot benefitSnapshot) {
        return benefitSnapshot instanceof CheckoutBenefitSnapshot.Applied a ? a.discountPaise() : 0L;
    }

    /** {@code merchandiseSubtotalPaise - benefitDiscountPaise}; never negative by construction. */
    public long payablePaise() {
        return merchandiseSubtotalPaise - benefitDiscountPaise;
    }
}
