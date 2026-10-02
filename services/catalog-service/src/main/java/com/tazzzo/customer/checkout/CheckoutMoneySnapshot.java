package com.tazzzo.customer.checkout;

/**
 * The ADVISORY commerce-money view the Checkout service computed when it created the quote, persisted with the quote
 * and never recomputed (an idempotent replay and a GET return it unchanged). Same V1 formula as the Order's money
 * snapshot, deliberately a SEPARATE type (different owner and authority level; Checkout never depends on Order):
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
 * Payment authority and NOT a price lock: nothing is authorized, captured or collected, and Order placement computes
 * its own authoritative money independently; the two may legitimately differ.
 *
 * <p>Sources, enforced by {@link CheckoutQuote}: {@code merchandiseSubtotalPaise} is the quote's own canonical
 * {@code subtotalPaise}; {@code benefitDiscountPaise} is the STORED advisory {@link CheckoutBenefitSnapshot} discount
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

    /** From the quote's canonical subtotal and its ALREADY-STORED advisory Benefits snapshot (no Benefits call). */
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
