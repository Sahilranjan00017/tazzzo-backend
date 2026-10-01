package com.tazzzo.benefits;

import com.tazzzo.common.money.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenefitRuleTest {

    static final String PLAN = "TAZZZO_PLUS_MONTHLY";

    static BenefitRule rule(int version, long minimumPaise, int bps) {
        return new BenefitRule(PLAN, version, Money.ofInrPaise(minimumPaise), new DiscountBps(bps));
    }

    @Test
    void a_valid_rule_round_trips() {
        BenefitRule r = rule(1, 50_000, 500);
        assertThat(r.planId()).isEqualTo(PLAN);
        assertThat(r.planVersion()).isEqualTo(1);
        assertThat(r.minimumSubtotal()).isEqualTo(Money.ofInrPaise(50_000));
        assertThat(r.discountBps()).isEqualTo(new DiscountBps(500));
    }

    @Test
    void invalid_rules_are_rejected() {
        Money min = Money.ofInrPaise(100);
        DiscountBps bps = new DiscountBps(500);
        assertThatThrownBy(() -> new BenefitRule(null, 1, min, bps)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule("lower_case", 1, min, bps)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule("AB", 1, min, bps)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule(PLAN, 0, min, bps)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule(PLAN, -1, min, bps)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule(PLAN, 1, null, bps)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule(PLAN, 1, Money.ofInrPaise(0), bps))
                .as("a zero minimum is not a threshold").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule(PLAN, 1, min, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenefitRule(PLAN, 1, min, new DiscountBps(0)))
                .as("a zero-bps rule is a meaningless promotion").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_rule_must_discount_at_least_one_paise_at_its_own_threshold() {
        // floor(9,999 * 1 / 10,000) = 0 -> the threshold itself would be worthless
        assertThatThrownBy(() -> rule(1, 9_999, 1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1 paise");
        assertThatThrownBy(() -> rule(1, 1, 9_999)).as("floor(1 * 9999 / 10000) = 0")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(1, 19, 500)).as("floor(19 * 500 / 10000) = 0")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rule(1, 0, 500)).as("a zero threshold stays rejected")
                .isInstanceOf(IllegalArgumentException.class);

        // the lowest valid pairs sit exactly on the boundary
        assertThat(rule(1, 10_000, 1).discountBps().applyTo(Money.ofInrPaise(10_000))).isEqualTo(Money.ofInrPaise(1));
        assertThat(rule(1, 20, 500).discountBps().applyTo(Money.ofInrPaise(20))).isEqualTo(Money.ofInrPaise(1));
        assertThat(rule(1, 2, 5_000).minimumSubtotal()).isEqualTo(Money.ofInrPaise(2));
        assertThat(rule(1, 1, 10_000).minimumSubtotal()).as("1 paise at 100%: every non-empty basket")
                .isEqualTo(Money.ofInrPaise(1));
        assertThat(rule(1, 50_000, 500).discountBps().applyTo(Money.ofInrPaise(50_000)))
                .isEqualTo(Money.ofInrPaise(2_500));
    }

    @Test
    void the_minimum_discounting_threshold_for_every_bps_is_ceil_of_ten_thousand_over_bps() {
        for (int bps : new int[]{1, 2, 3, 7, 99, 500, 3_333, 5_000, 9_999, 10_000}) {
            long minimumThatWorks = (10_000L + bps - 1) / bps;
            assertThat(rule(1, minimumThatWorks, bps)).isNotNull();
            if (minimumThatWorks > 1) {
                long below = minimumThatWorks - 1;
                int b = bps;
                assertThatThrownBy(() -> rule(1, below, b)).as("bps=%d minimum=%d", b, below)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void the_threshold_is_inclusive() {
        BenefitRule r = rule(1, 50_000, 500);
        assertThat(r.admits(Money.ofInrPaise(49_999))).isFalse();
        assertThat(r.admits(Money.ofInrPaise(50_000))).isTrue();
        assertThat(r.admits(Money.ofInrPaise(50_001))).isTrue();
        assertThat(r.admits(Money.ofInrPaise(0))).isFalse();
    }
}
