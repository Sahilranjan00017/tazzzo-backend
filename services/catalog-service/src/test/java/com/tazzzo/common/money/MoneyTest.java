package com.tazzzo.common.money;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test void zero_is_allowed() {
        assertEquals(0L, Money.ofInrPaise(0).paise());
        assertEquals(Currency.INR, Money.ofInrPaise(0).currency());
    }

    @Test void positive_amount() {
        assertEquals(26500L, Money.ofInrPaise(26500).paise());
    }

    @Test void negative_rejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofInrPaise(-1));
        assertThrows(IllegalArgumentException.class, () -> new Money(-100, Currency.INR));
    }

    @Test void null_currency_rejected() {
        assertThrows(NullPointerException.class, () -> new Money(100, null));
    }

    @Test void max_realistic_amount() {
        assertEquals(Long.MAX_VALUE, Money.ofInrPaise(Long.MAX_VALUE).paise());
    }

    @Test void addition_is_overflow_safe() {
        Money a = Money.ofInrPaise(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> a.plus(Money.ofInrPaise(1)));
        assertEquals(300L, Money.ofInrPaise(100).plus(Money.ofInrPaise(200)).paise());
    }

    @Test void subtraction_non_negative_and_overflow_safe() {
        assertEquals(50L, Money.ofInrPaise(200).minus(Money.ofInrPaise(150)).paise());
        assertThrows(IllegalArgumentException.class,
                () -> Money.ofInrPaise(100).minus(Money.ofInrPaise(200)));
    }

    @Test void equality_and_hashcode() {
        assertEquals(Money.ofInrPaise(500), Money.ofInrPaise(500));
        assertEquals(Money.ofInrPaise(500).hashCode(), Money.ofInrPaise(500).hashCode());
        assertNotEquals(Money.ofInrPaise(500), Money.ofInrPaise(501));
    }
}
