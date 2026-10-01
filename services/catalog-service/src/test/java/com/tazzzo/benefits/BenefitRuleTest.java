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
    void the_threshold_is_inclusive() {
        BenefitRule r = rule(1, 50_000, 500);
        assertThat(r.admits(Money.ofInrPaise(49_999))).isFalse();
        assertThat(r.admits(Money.ofInrPaise(50_000))).isTrue();
        assertThat(r.admits(Money.ofInrPaise(50_001))).isTrue();
        assertThat(r.admits(Money.ofInrPaise(0))).isFalse();
    }
}
