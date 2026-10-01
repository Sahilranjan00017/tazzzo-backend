package com.tazzzo.benefits;

import com.tazzzo.common.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.util.Arrays;

import static com.tazzzo.benefits.BenefitRuleTest.PLAN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real YAML -> {@code @ConfigurationProperties} binding of {@code tazzzo.benefits}, through the same Binder Spring
 * Boot uses, then through the very factory method the application context calls: whatever is malformed must fail at
 * configuration construction (application start), never during an evaluation. Production configures NO rule.
 */
class BenefitsConfigBindingTest {

    private static BenefitRuleSource load(String yaml) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> ps : new YamlPropertySourceLoader().load("test.yml",
                new ByteArrayResource(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
            env.getPropertySources().addLast(ps);
        }
        BenefitsRuleProperties props = Binder.get(env).bind("tazzzo.benefits", BenefitsRuleProperties.class)
                .orElseGet(BenefitsRuleProperties::new);
        return new BenefitsConfig().benefitRuleSource(props); // the exact factory the application context invokes
    }

    private static String rule(String body) {
        return "tazzzo:\n  benefits:\n    rules:\n      - {" + body + "}\n";
    }

    private static final String GOOD = "plan-id: TAZZZO_PLUS_MONTHLY, plan-version: 1, minimum-subtotal-paise: 12345, "
            + "currency: INR, discount-bps: 250";

    @Test
    void an_absent_property_an_empty_list_and_a_null_node_all_mean_no_rule() throws IOException {
        for (String yaml : Arrays.asList("tazzzo:\n  other: 1\n", "tazzzo:\n  benefits:\n    rules: []\n",
                "tazzzo:\n  benefits:\n    rules:\n")) {
            assertThat(load(yaml).find(PLAN, 1)).as(yaml).isEmpty();
        }
    }

    @Test
    void a_valid_rule_binds_with_the_exact_values() throws IOException {
        BenefitRule rule = load(rule(GOOD)).find(PLAN, 1).orElseThrow();

        assertThat(rule.minimumSubtotal()).isEqualTo(Money.ofInrPaise(12_345));
        assertThat(rule.discountBps()).isEqualTo(new DiscountBps(250));
    }

    @Test
    void every_malformed_configuration_fails_at_construction_not_at_evaluation() {
        for (String bad : new String[]{
                GOOD + "}\n      - {" + GOOD.replace("minimum-subtotal-paise: 12345", "minimum-subtotal-paise: 99"), // duplicate key
                GOOD.replace("discount-bps: 250", "discount-bps: 0"),                  // zero bps
                GOOD.replace("discount-bps: 250", "discount-bps: 10001"),              // > 100%
                GOOD.replace("12345", "0"),                                            // zero threshold
                GOOD.replace("12345", "9999").replace("250", "1"),                     // 0 paise at its own threshold
                "plan-id: TAZZZO_PLUS_MONTHLY",                                        // missing fields
                GOOD.replace("currency: INR", "currency: USD"),                        // unknown currency
                GOOD.replace("plan-version: 1", "plan-version: 0"),                    // bad version
                GOOD.replace("TAZZZO_PLUS_MONTHLY", "bad id")}) {                      // bad plan id
            assertThatThrownBy(() -> load(rule(bad))).as(bad)
                    .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        }
    }

    @Test
    void a_non_numeric_value_is_a_binding_failure() {
        assertThatThrownBy(() -> load(rule(GOOD.replace("discount-bps: 250", "discount-bps: abc"))))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> load(rule(GOOD.replace("12345", "lots")))).isInstanceOf(BindException.class);
    }
}
