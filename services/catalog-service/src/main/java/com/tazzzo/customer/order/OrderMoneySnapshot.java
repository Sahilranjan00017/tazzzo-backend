package com.tazzzo.customer.order;

/**
 * The AUTHORITATIVE commerce-money fact of an Order under the V1 money model, frozen at placement:
 * <pre>
 *   payablePaise = merchandiseSubtotalPaise - benefitDiscountPaise
 * </pre>
 * V1 scope (decisions, not claims that these can never exist): selling prices are treated as tax-inclusive, so there is
 * NO separate tax/GST calculation; there are NO delivery, platform, handling, packaging, small-cart, surge or service
 * fees; NO coupons; Tazzzo Coins are NOT spendable; there is NO wallet. None of those appears here, not even as a
 * zero placeholder: a zero would claim the component was evaluated and found to be zero. A later component is added as
 * an explicit additive field, and a historical Order (which has no such component) is never recomputed.
 *
 * <p>{@code payablePaise} is DERIVED, never accepted from a caller, so an inconsistent payable cannot be constructed
 * (reconstruction of a persisted document verifies the stored payable against this formula). {@code 0} is a valid
 * payable (a 100% Benefits discount); there is no minimum payable. It says nothing about Payment: nothing is
 * authorized, captured or collected, and no payment state exists.
 *
 * <p>Sources, enforced by {@code Order}: {@code merchandiseSubtotalPaise} is the Order's own canonical
 * {@code subtotalPaise} (built from canonical lines after Pricing revalidation, never from a client, the Cart or the
 * Checkout preview), and {@code benefitDiscountPaise} is the authoritative discount of the Order-side
 * {@link OrderBenefitSnapshot} (0 for no benefit), never recomputed from a rate. ABSENCE on an Order means a legacy
 * Order created before this model: it never means {@code payable = subtotal} or {@code 0}.
 */
public record OrderMoneySnapshot(long merchandiseSubtotalPaise, long benefitDiscountPaise) {

    public OrderMoneySnapshot {
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

    public static OrderMoneySnapshot from(long merchandiseSubtotalPaise, long benefitDiscountPaise) {
        return new OrderMoneySnapshot(merchandiseSubtotalPaise, benefitDiscountPaise);
    }

    /** {@code merchandiseSubtotalPaise - benefitDiscountPaise}; never negative by construction. */
    public long payablePaise() {
        return merchandiseSubtotalPaise - benefitDiscountPaise;
    }
}
