package com.tazzzo.customer.order;

/**
 * The PUBLIC-SAFE projection of an Order's persisted, AUTHORITATIVE {@link OrderMoneySnapshot}: the only money view the
 * HTTP layer ever sees (the Order counterpart of Checkout's advisory {@code CheckoutMoneyPreview}). It carries only the
 * three commerce amounts (no Benefits reason, no Membership or plan identity, no persistence detail) and is a pure
 * projection of PERSISTED values: it never re-evaluates Benefits or recomputes anything. {@code payablePaise} is the
 * commerce amount owed for the Order, not a Payment fact (nothing is authorized, captured or collected). ABSENCE (no view
 * at all) is a legacy Order created before the money model, never a zero payable.
 */
record OrderMoneyView(long merchandiseSubtotalPaise, long benefitDiscountPaise, long payablePaise) {

    OrderMoneyView {
        if (merchandiseSubtotalPaise < 0 || benefitDiscountPaise < 0 || benefitDiscountPaise > merchandiseSubtotalPaise
                || payablePaise != merchandiseSubtotalPaise - benefitDiscountPaise) {
            throw new IllegalArgumentException("inconsistent order money view");
        }
    }

    static OrderMoneyView from(OrderMoneySnapshot s) {
        return new OrderMoneyView(s.merchandiseSubtotalPaise(), s.benefitDiscountPaise(), s.payablePaise());
    }
}
