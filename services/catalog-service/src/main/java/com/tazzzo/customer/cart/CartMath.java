package com.tazzzo.customer.cart;

/** PR-12C — int64 paise arithmetic only; overflow is an exception, never a wrapped negative. */
final class CartMath {

    private CartMath() {
    }

    /** @throws ArithmeticException on overflow */
    static long lineTotal(long unitPricePaise, int quantity) {
        return Math.multiplyExact(unitPricePaise, (long) quantity);
    }

    /** @throws ArithmeticException on overflow */
    static long add(long subtotalPaise, long lineTotalPaise) {
        return Math.addExact(subtotalPaise, lineTotalPaise);
    }
}
