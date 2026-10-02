package com.tazzzo.wiring;

import com.tazzzo.benefits.BenefitRuleSource;
import com.tazzzo.benefits.BenefitsConfig;
import com.tazzzo.benefits.BenefitsRuleProperties;
import com.tazzzo.benefits.ConfigBackedBenefitRuleSource;
import com.tazzzo.membership.MembershipConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static Benefits rule configuration is cross-validated against the static Membership plan configuration at
 * application start, through the real Spring configuration classes and the real composition adapter: an orphan rule
 * fails the start (never ignored, dropped or turned into NO_RULE); a plan without a rule, or no rule at all, is valid.
 * Fixtures only: no production rule is configured here or anywhere.
 */
class BenefitsPlanCrossValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MembershipConfig.class, BenefitPlanCatalogConfig.class, BenefitsConfig.class);

    private static List<String> plan(int i, String planId, int version, String from, String until) {
        List<String> p = new ArrayList<>();
        String k = "tazzzo.membership.plans[" + i + "].";
        p.add(k + "plan-id=" + planId);
        p.add(k + "version=" + version);
        p.add(k + "price-paise=9900");
        p.add(k + "currency=INR");
        p.add(k + "period-months=1");
        p.add(k + "effective-from=" + from);
        if (until != null) p.add(k + "effective-until=" + until);
        return p;
    }

    private static List<String> rule(int i, String planId, int version) {
        String k = "tazzzo.benefits.rules[" + i + "].";
        return List.of(k + "plan-id=" + planId, k + "plan-version=" + version, k + "minimum-subtotal-paise=10000",
                k + "currency=INR", k + "discount-bps=500");
    }

    @SafeVarargs
    private static String[] props(List<String>... groups) {
        List<String> all = new ArrayList<>();
        for (List<String> g : groups) all.addAll(g);
        return all.toArray(String[]::new);
    }

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String SWITCH = "2026-06-01T00:00:00Z";

    // ---------- valid configurations ----------

    @Test
    void zero_benefit_rules_with_configured_plans_start_and_every_plan_has_no_rule() {
        runner.withPropertyValues(props(plan(0, "PLAN_A", 1, FROM, null))).run(context -> {
            assertThat(context).hasNotFailed();
            ConfigBackedBenefitRuleSource source = (ConfigBackedBenefitRuleSource) context.getBean(BenefitRuleSource.class);
            assertThat(source.ruleCount()).isZero();
            assertThat(source.find("PLAN_A", 1)).as("a plan without a rule is NO_RULE").isEmpty();
        });
    }

    @Test
    void a_rule_for_exactly_a_configured_plan_and_version_starts() {
        runner.withPropertyValues(props(plan(0, "PLAN_A", 1, FROM, null), rule(0, "PLAN_A", 1))).run(context -> {
            assertThat(context).hasNotFailed();
            BenefitRuleSource source = context.getBean(BenefitRuleSource.class);
            assertThat(source.find("PLAN_A", 1)).isPresent();
            assertThat(((ConfigBackedBenefitRuleSource) source).ruleCount()).isEqualTo(1);
        });
    }

    @Test
    void a_plan_is_not_required_to_have_a_rule_and_a_subset_of_plan_versions_may_have_one() {
        runner.withPropertyValues(props(plan(0, "PLAN_A", 1, FROM, SWITCH), plan(1, "PLAN_A", 2, SWITCH, null),
                rule(0, "PLAN_A", 1))).run(context -> {
            assertThat(context).hasNotFailed();
            BenefitRuleSource source = context.getBean(BenefitRuleSource.class);
            assertThat(source.find("PLAN_A", 1)).isPresent();
            assertThat(source.find("PLAN_A", 2)).as("v2 is configured but has no rule: NO_RULE").isEmpty();
        });
    }

    @Test
    void multiple_rules_for_distinct_configured_plan_versions_start() {
        runner.withPropertyValues(props(plan(0, "PLAN_A", 1, FROM, SWITCH), plan(1, "PLAN_A", 2, SWITCH, null),
                plan(2, "PLAN_B", 1, FROM, null), rule(0, "PLAN_A", 1), rule(1, "PLAN_A", 2), rule(2, "PLAN_B", 1)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BenefitRuleSource source = context.getBean(BenefitRuleSource.class);
                    assertThat(source.find("PLAN_A", 1)).isPresent();
                    assertThat(source.find("PLAN_A", 2)).isPresent();
                    assertThat(source.find("PLAN_B", 1)).isPresent();
                    assertThat(((ConfigBackedBenefitRuleSource) source).ruleCount()).isEqualTo(3);
                });
    }

    // ---------- invalid configurations ----------

    private void assertStartupFails(String[] properties, String... messageParts) {
        runner.withPropertyValues(properties).run((AssertableApplicationContext context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContainingAll(messageParts);
        });
    }

    @Test
    void a_rule_for_an_unknown_plan_id_fails_startup_and_names_the_plan_and_version() {
        assertStartupFails(props(plan(0, "PLAN_A", 1, FROM, null), rule(0, "PLAN_Z", 1)),
                "planId=PLAN_Z", "planVersion=1");
    }

    @Test
    void a_rule_for_a_known_plan_but_unknown_version_fails_startup_and_names_the_plan_and_version() {
        assertStartupFails(props(plan(0, "PLAN_A", 1, FROM, null), rule(0, "PLAN_A", 2)),
                "planId=PLAN_A", "planVersion=2");
    }

    @Test
    void one_orphan_among_valid_rules_still_fails_startup() {
        assertStartupFails(props(plan(0, "PLAN_A", 1, FROM, SWITCH), plan(1, "PLAN_A", 2, SWITCH, null),
                rule(0, "PLAN_A", 1), rule(1, "PLAN_A", 3)), "planId=PLAN_A", "planVersion=3");
    }

    @Test
    void the_orphan_message_identifies_the_plan_without_dumping_commercial_values() {
        runner.withPropertyValues(props(plan(0, "PLAN_A", 1, FROM, null), rule(0, "PLAN_Z", 7))).run(context -> {
            String message = context.getStartupFailure().getCause() == null ? "" : rootMessage(context.getStartupFailure());
            assertThat(message).contains("planId=PLAN_Z").contains("planVersion=7")
                    .doesNotContain("10000").doesNotContain("discount").doesNotContain("500")
                    .doesNotContain("9900");
        });
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }

    @Test
    void a_duplicate_rule_key_and_a_malformed_rule_still_fail_startup() {
        List<String> duplicate = new ArrayList<>(rule(0, "PLAN_A", 1));
        duplicate.addAll(rule(1, "PLAN_A", 1));
        assertStartupFails(props(plan(0, "PLAN_A", 1, FROM, null), duplicate), "duplicate benefit rule");

        assertStartupFails(props(plan(0, "PLAN_A", 1, FROM, null),
                List.of("tazzzo.benefits.rules[0].plan-id=PLAN_A", "tazzzo.benefits.rules[0].plan-version=1",
                        "tazzzo.benefits.rules[0].minimum-subtotal-paise=10000", "tazzzo.benefits.rules[0].currency=INR",
                        "tazzzo.benefits.rules[0].discount-bps=0")), "bps");
    }

    // ---------- production configuration and immutability ----------

    @Test
    void the_checked_in_production_configuration_defines_zero_benefit_rules_and_starts() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(MembershipConfig.class, BenefitPlanCatalogConfig.class, BenefitsConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BenefitsRuleProperties.class).getRules())
                            .as("NO production Benefits rule is ratified or configured").isEmpty();
                    assertThat(((ConfigBackedBenefitRuleSource) context.getBean(BenefitRuleSource.class)).ruleCount())
                            .isZero();
                    assertThat(context.getBean(BenefitRuleSource.class).find("TAZZZO_PLUS_MONTHLY", 1)).isEmpty();
                });
    }

    @Test
    void the_rule_source_does_not_follow_later_changes_to_the_bound_properties() {
        runner.withPropertyValues(props(plan(0, "PLAN_A", 1, FROM, null), rule(0, "PLAN_A", 1))).run(context -> {
            ConfigBackedBenefitRuleSource source = (ConfigBackedBenefitRuleSource) context.getBean(BenefitRuleSource.class);
            BenefitsRuleProperties properties = context.getBean(BenefitsRuleProperties.class);

            properties.getRules().clear();                       // a runtime mutation of the bound properties ...
            BenefitsRuleProperties.Rule other = new BenefitsRuleProperties.Rule();
            other.setPlanId("PLAN_A");
            properties.getRules().add(other);

            assertThat(source.ruleCount()).as("... never reaches the already-built source").isEqualTo(1);
            assertThat(source.find("PLAN_A", 1).orElseThrow().discountBps().bps()).isEqualTo(500);
        });
    }
}
