package com.tazzzo.benefits;

import com.tazzzo.common.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscountBpsTest {

    private static Money inr(long paise) {
        return Money.ofInrPaise(paise);
    }

    @Test
    void bps_must_lie_within_zero_and_ten_thousand() {
        for (int bad : List.of(Integer.MIN_VALUE, -1, 10_001, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> new DiscountBps(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int ok : List.of(0, 1, 500, 9_999, 10_000)) {
            assertThat(new DiscountBps(ok).bps()).isEqualTo(ok);
        }
    }

    @Test
    void the_discount_is_floor_of_subtotal_times_bps_over_ten_thousand() {
        DiscountBps five = new DiscountBps(500); // 5% (a TEST FIXTURE, not launch policy)
        assertThat(five.applyTo(inr(0))).isEqualTo(inr(0));
        assertThat(five.applyTo(inr(1))).isEqualTo(inr(0));
        assertThat(five.applyTo(inr(19))).as("0.95 floors to 0").isEqualTo(inr(0));
        assertThat(five.applyTo(inr(20))).isEqualTo(inr(1));
        assertThat(five.applyTo(inr(21))).isEqualTo(inr(1));
        assertThat(five.applyTo(inr(50_000))).isEqualTo(inr(2_500));
        assertThat(five.applyTo(inr(99_999))).as("4999.95 floors to 4999").isEqualTo(inr(4_999));
        assertThat(new DiscountBps(1).applyTo(inr(9_999))).isEqualTo(inr(0));
        assertThat(new DiscountBps(1).applyTo(inr(10_000))).isEqualTo(inr(1));
        assertThat(new DiscountBps(9_999).applyTo(inr(10_001))).as("9999.9999 floors").isEqualTo(inr(9_999));
    }

    @Test
    void zero_bps_discounts_nothing_and_full_bps_discounts_exactly_the_subtotal() {
        for (long paise : List.of(0L, 1L, 9_999L, 10_001L, Long.MAX_VALUE)) {
            assertThat(new DiscountBps(0).applyTo(inr(paise))).isEqualTo(inr(0));
            assertThat(new DiscountBps(10_000).applyTo(inr(paise))).isEqualTo(inr(paise));
        }
    }

    @Test
    void extreme_values_never_overflow_and_never_exceed_the_subtotal() {
        for (int bps : List.of(1, 500, 9_999, 10_000)) {
            Money discount = new DiscountBps(bps).applyTo(inr(Long.MAX_VALUE));
            BigInteger exact = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(bps))
                    .divide(BigInteger.valueOf(10_000));
            assertThat(BigInteger.valueOf(discount.paise())).isEqualTo(exact);
            assertThat(discount.paise()).isLessThanOrEqualTo(Long.MAX_VALUE);
        }
    }

    @Test
    void the_overflow_free_formula_equals_exact_big_integer_floor_division() {
        Random random = new Random(16_000);
        for (int i = 0; i < 20_000; i++) {
            long paise = i % 3 == 0 ? Math.abs(random.nextLong() % 1_000_000L)
                    : i % 3 == 1 ? Math.abs(random.nextLong() % 1_000_000_000_000L) : Math.abs(random.nextLong() % Long.MAX_VALUE);
            int bps = random.nextInt(10_001);
            BigInteger exact = BigInteger.valueOf(paise).multiply(BigInteger.valueOf(bps))
                    .divide(BigInteger.valueOf(10_000));
            Money discount = new DiscountBps(bps).applyTo(inr(paise));
            assertThat(discount.paise()).as("paise=%d bps=%d", paise, bps).isEqualTo(exact.longValueExact());
            assertThat(discount.paise()).isLessThanOrEqualTo(paise);
        }
    }

    @Test
    void a_null_subtotal_is_rejected() {
        assertThatThrownBy(() -> new DiscountBps(500).applyTo(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
