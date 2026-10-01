package com.tazzzo.benefits;

import com.tazzzo.common.money.Money;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tazzzo.benefits.BenefitRuleTest.PLAN;
import static com.tazzzo.benefits.BenefitRuleTest.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigBackedBenefitRuleSourceTest {

    @Test
    void lookup_is_exact_on_plan_id_and_version() {
        BenefitRule v1 = rule(1, 50_000, 500);
        BenefitRule v2 = rule(2, 60_000, 700);
        ConfigBackedBenefitRuleSource source = new ConfigBackedBenefitRuleSource(List.of(v1, v2));

        assertThat(source.find(PLAN, 1)).hasValue(v1);
        assertThat(source.find(PLAN, 2)).hasValue(v2);
    }

    @Test
    void an_unknown_version_or_plan_has_no_rule_and_never_falls_back() {
        ConfigBackedBenefitRuleSource source = new ConfigBackedBenefitRuleSource(
                List.of(rule(1, 50_000, 500), rule(2, 60_000, 700)));

        assertThat(source.find(PLAN, 3)).as("no latest-version fallback").isEmpty();
        assertThat(source.find(PLAN, 0)).isEmpty();
        assertThat(source.find("OTHER_PLAN", 1)).as("no plan-id-only or default fallback").isEmpty();
    }

    @Test
    void a_later_version_does_not_affect_an_earlier_one() {
        BenefitRule v1 = rule(1, 50_000, 500);
        ConfigBackedBenefitRuleSource onlyV1 = new ConfigBackedBenefitRuleSource(List.of(v1));
        ConfigBackedBenefitRuleSource both = new ConfigBackedBenefitRuleSource(List.of(v1, rule(2, 10_000, 9_999)));

        assertThat(both.find(PLAN, 1)).isEqualTo(onlyV1.find(PLAN, 1));
    }

    @Test
    void an_empty_source_is_valid_the_production_default() {
        assertThat(new ConfigBackedBenefitRuleSource(List.of()).find(PLAN, 1)).isEmpty();
    }

    @Test
    void duplicate_keys_and_null_input_fail_construction() {
        assertThatThrownBy(() -> new ConfigBackedBenefitRuleSource(List.of(rule(1, 100, 500), rule(1, 200, 600))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new ConfigBackedBenefitRuleSource(null)).isInstanceOf(IllegalStateException.class);
        List<BenefitRule> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new ConfigBackedBenefitRuleSource(withNull)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_loaded_set_is_immutable_to_later_changes_of_the_input_list() {
        List<BenefitRule> input = new ArrayList<>(List.of(rule(1, 50_000, 500)));
        ConfigBackedBenefitRuleSource source = new ConfigBackedBenefitRuleSource(input);

        input.clear();
        input.add(rule(2, 10_000, 1));

        assertThat(source.find(PLAN, 1)).isPresent();
        assertThat(source.find(PLAN, 2)).isEmpty();
    }

    @Test
    void properties_bind_to_validated_rules_and_reject_bad_entries() {
        BenefitsRuleProperties props = new BenefitsRuleProperties();
        assertThat(props.toRules()).as("the production default configures no rule").isEmpty();

        props.setRules(List.of(propertiesRule(PLAN, 1, 50_000, "INR", 500)));
        assertThat(props.toRules()).containsExactly(rule(1, 50_000, 500));

        for (BenefitsRuleProperties.Rule bad : List.of(
                propertiesRule(PLAN, 1, 50_000, "INR", 0),        // zero bps
                propertiesRule(PLAN, 1, 50_000, "INR", 10_001),   // > 100%
                propertiesRule(PLAN, 1, 0, "INR", 500),           // zero threshold
                propertiesRule(PLAN, 1, 9_999, "INR", 1),         // floors to 0 paise AT its own threshold
                propertiesRule(PLAN, 1, -5, "INR", 500),          // negative money
                propertiesRule(PLAN, 1, 50_000, "USD", 500),      // unknown currency
                propertiesRule(PLAN, 1, 50_000, null, 500),       // missing currency
                propertiesRule(PLAN, 0, 50_000, "INR", 500),      // bad version
                propertiesRule("bad id", 1, 50_000, "INR", 500))) { // bad plan id
            BenefitsRuleProperties p = new BenefitsRuleProperties();
            p.setRules(List.of(bad));
            assertThatThrownBy(p::toRules).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static BenefitsRuleProperties.Rule propertiesRule(String plan, int version, long min, String currency,
                                                              int bps) {
        BenefitsRuleProperties.Rule r = new BenefitsRuleProperties.Rule();
        r.setPlanId(plan);
        r.setPlanVersion(version);
        r.setMinimumSubtotalPaise(min);
        r.setCurrency(currency);
        r.setDiscountBps(bps);
        return r;
    }

    @Test
    void money_is_reused_not_reinvented() {
        assertThat(rule(1, 100, 500).minimumSubtotal()).isInstanceOf(Money.class);
    }
}
