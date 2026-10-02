package com.tazzzo.customer.checkout;

/**
 * The PUBLIC-SAFE projection of a quote's stored advisory {@link CheckoutMoneySnapshot}: the only money view the HTTP
 * layer ever sees. It carries only the three commerce amounts (no Benefits reason, no identity) and is a pure
 * projection of PERSISTED values: it never re-evaluates Benefits, reads Membership or rules, or recomputes anything.
 * ABSENCE (no preview at all) is a quote created before the money model existed, never a zero payable.
 */
record CheckoutMoneyPreview(long merchandiseSubtotalPaise, long benefitDiscountPaise, long payablePaise) {

    CheckoutMoneyPreview {
        if (merchandiseSubtotalPaise < 0 || benefitDiscountPaise < 0 || benefitDiscountPaise > merchandiseSubtotalPaise
                || payablePaise != merchandiseSubtotalPaise - benefitDiscountPaise) {
            throw new IllegalArgumentException("inconsistent money preview");
        }
    }

    static CheckoutMoneyPreview from(CheckoutMoneySnapshot s) {
        return new CheckoutMoneyPreview(s.merchandiseSubtotalPaise(), s.benefitDiscountPaise(), s.payablePaise());
    }
}
