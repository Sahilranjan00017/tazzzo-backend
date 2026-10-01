package com.tazzzo.membership;

import com.tazzzo.common.money.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-16A-1 -- the config-backed plan source validates the WHOLE plan set at application startup: a bad
 * configuration fails the context start, it is never discovered at runtime.
 */
class MembershipPlanConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MembershipConfig.class);

    /** Property values for plan {@code i}; a null value omits the key. */
    private static List<String> plan(int i, String planId, String version, String price, String currency,
                                     String months, String from, String until) {
        List<String> p = new ArrayList<>();
        String k = "tazzzo.membership.plans[" + i + "].";
        if (planId != null) p.add(k + "plan-id=" + planId);
        if (version != null) p.add(k + "version=" + version);
        if (price != null) p.add(k + "price-paise=" + price);
        if (currency != null) p.add(k + "currency=" + currency);
        if (months != null) p.add(k + "period-months=" + months);
        if (from != null) p.add(k + "effective-from=" + from);
        if (until != null) p.add(k + "effective-until=" + until);
        return p;
    }

    private static List<String> good(int i, String version, String from, String until) {
        return plan(i, "TAZZZO_PLUS_MONTHLY", version, "9900", "INR", "1", from, until);
    }

    private void assertStartupFails(List<String> properties, String messagePart) {
        runner.withPropertyValues(properties.toArray(String[]::new)).run((AssertableApplicationContext context) -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(messagePart);
        });
    }

    // ---------- golden: the REAL checked-in launch configuration ----------

    @Test
    void golden_launch_plan_is_exactly_TAZZZO_PLUS_MONTHLY_v1_9900_paise_INR_one_month() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(MembershipConfig.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    MembershipPlanSource source = context.getBean(MembershipPlanSource.class);
                    MembershipPlan v1 = source.find("TAZZZO_PLUS_MONTHLY", 1).orElseThrow();
                    assertThat(v1.planId()).isEqualTo("TAZZZO_PLUS_MONTHLY");
                    assertThat(v1.version()).isEqualTo(1);
                    assertThat(v1.price().paise()).isEqualTo(9900L);
                    assertThat(v1.price().currency()).isEqualTo(Currency.INR);
                    assertThat(v1.periodMonths()).isEqualTo(1);
                    assertThat(v1.effectiveUntil()).isNull();
                    assertThat(source.find("TAZZZO_PLUS_MONTHLY", 2)).isEmpty();
                    assertThat(source.find("OTHER_PLAN", 1)).isEmpty();
                });
    }

    // ---------- startup failures ----------

    @Test
    void no_plans_fails_startup() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("at least one plan");
        });
    }

    @Test
    void duplicate_plan_and_version_fails_startup() {
        List<String> p = good(0, "1", "2026-01-01T00:00:00Z", null);
        p.addAll(good(1, "1", "2026-01-01T00:00:00Z", null));
        assertStartupFails(p, "duplicate membership plan");
    }

    @Test
    void a_bad_plan_id_fails_startup() {
        assertStartupFails(plan(0, "bad-id", "1", "9900", "INR", "1", "2026-01-01T00:00:00Z", null), "plan id");
        assertStartupFails(plan(0, "lower_case", "1", "9900", "INR", "1", "2026-01-01T00:00:00Z", null), "plan id");
        assertStartupFails(plan(0, null, "1", "9900", "INR", "1", "2026-01-01T00:00:00Z", null), "plan id");
    }

    @Test
    void a_bad_version_fails_startup() {
        assertStartupFails(good(0, "0", "2026-01-01T00:00:00Z", null), "version");
        assertStartupFails(good(0, "-3", "2026-01-01T00:00:00Z", null), "version");
    }

    @Test
    void a_zero_or_negative_price_fails_startup() {
        assertStartupFails(plan(0, "TAZZZO_PLUS_MONTHLY", "1", "0", "INR", "1", "2026-01-01T00:00:00Z", null), "price");
        assertStartupFails(plan(0, "TAZZZO_PLUS_MONTHLY", "1", "-1", "INR", "1", "2026-01-01T00:00:00Z", null), "non-negative");
    }

    @Test
    void a_non_INR_or_missing_currency_fails_startup() {
        assertStartupFails(plan(0, "TAZZZO_PLUS_MONTHLY", "1", "9900", "USD", "1", "2026-01-01T00:00:00Z", null), "USD");
        assertStartupFails(plan(0, "TAZZZO_PLUS_MONTHLY", "1", "9900", null, "1", "2026-01-01T00:00:00Z", null), "");
    }

    @Test
    void a_period_outside_1_to_120_months_fails_startup() {
        assertStartupFails(plan(0, "TAZZZO_PLUS_MONTHLY", "1", "9900", "INR", "0", "2026-01-01T00:00:00Z", null), "periodMonths");
        assertStartupFails(plan(0, "TAZZZO_PLUS_MONTHLY", "1", "9900", "INR", "121", "2026-01-01T00:00:00Z", null), "periodMonths");
    }

    @Test
    void a_missing_effective_from_or_a_bad_effective_interval_fails_startup() {
        assertStartupFails(plan(0, "TAZZZO_PLUS_MONTHLY", "1", "9900", "INR", "1", null, null), "effectiveFrom");
        assertStartupFails(good(0, "1", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"), "after effectiveFrom");
        assertStartupFails(good(0, "1", "2026-01-01T00:00:00Z", "2025-01-01T00:00:00Z"), "after effectiveFrom");
    }

    @Test
    void overlapping_version_windows_fail_startup() {
        List<String> p = good(0, "1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        p.addAll(good(1, "2", "2026-06-01T00:00:00Z", null));
        assertStartupFails(p, "overlapping or out-of-order");
    }

    @Test
    void out_of_order_version_windows_fail_startup() {
        List<String> p = good(0, "1", "2027-01-01T00:00:00Z", "2027-06-01T00:00:00Z");
        p.addAll(good(1, "2", "2026-01-01T00:00:00Z", null));
        assertStartupFails(p, "overlapping or out-of-order");
    }

    @Test
    void an_open_ended_non_final_version_fails_startup() {
        List<String> p = good(0, "1", "2026-01-01T00:00:00Z", null);
        p.addAll(good(1, "2", "2027-01-01T00:00:00Z", null));
        assertStartupFails(p, "open-ended but is not the final version");
    }

    // ---------- the valid shapes ----------

    @Test
    void a_valid_back_to_back_version_chain_starts_and_resolves_each_version_explicitly() {
        List<String> p = good(0, "1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        p.addAll(plan(1, "TAZZZO_PLUS_MONTHLY", "2", "12900", "INR", "1", "2027-01-01T00:00:00Z", null));
        runner.withPropertyValues(p.toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            MembershipPlanSource source = context.getBean(MembershipPlanSource.class);
            assertThat(source.find("TAZZZO_PLUS_MONTHLY", 1).orElseThrow().price().paise()).isEqualTo(9900L);
            assertThat(source.find("TAZZZO_PLUS_MONTHLY", 2).orElseThrow().price().paise()).isEqualTo(12900L);
            assertThat(source.find("TAZZZO_PLUS_MONTHLY", 1).orElseThrow()
                    .isEffectiveAt(Instant.parse("2027-01-01T00:00:00Z"))).as("half-open: until is exclusive").isFalse();
            assertThat(source.find("TAZZZO_PLUS_MONTHLY", 2).orElseThrow()
                    .isEffectiveAt(Instant.parse("2027-01-01T00:00:00Z"))).as("half-open: from is inclusive").isTrue();
        });
    }
}
