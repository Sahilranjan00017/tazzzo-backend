package com.tazzzo.benefits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Builds the STATIC, process-local, immutable Benefits rule source from {@code tazzzo.benefits.rules}.
 *
 * <p>V1 policy: a {@link BenefitRule} is immutable for its {@code (planId, planVersion)}; changing a commercial term
 * (discount rate, minimum subtotal) for a Membership plan means a NEW Membership plan version. Every configured rule
 * must reference a Membership plan version that is configured ({@link BenefitPlanCatalog}); an orphan rule fails the
 * application start loudly (it is never ignored, dropped, turned into {@code NO_RULE} or re-pointed at another
 * version). The converse is not required: a plan version without a rule is valid and evaluates to {@code NO_RULE}.
 * Zero rules is valid (production configures none). Identical {@code tazzzo.membership.plans} AND
 * {@code tazzzo.benefits.rules} on every application instance is a deployment requirement (see ENGINEERING_STATUS).
 */
@Configuration
@EnableConfigurationProperties(BenefitsRuleProperties.class)
public class BenefitsConfig {

    private static final Logger log = LoggerFactory.getLogger(BenefitsConfig.class);

    @Bean
    public BenefitRuleSource benefitRuleSource(BenefitsRuleProperties properties, BenefitPlanCatalog plans) {
        List<BenefitRule> rules = properties.toRules();
        requireEveryRuleToReferenceAConfiguredPlan(rules, plans);
        ConfigBackedBenefitRuleSource source = new ConfigBackedBenefitRuleSource(rules);
        // the COUNT only: no rule identity or commercial value is logged
        log.info("benefits_rules_configured count={}", source.ruleCount());
        return source;
    }

    /** Fails the start for the first rule whose {@code (planId, planVersion)} is not a configured Membership plan. */
    static void requireEveryRuleToReferenceAConfiguredPlan(List<BenefitRule> rules, BenefitPlanCatalog plans) {
        for (BenefitRule rule : rules) {
            if (!plans.contains(rule.planId(), rule.planVersion())) {
                throw new IllegalStateException("tazzzo.benefits.rules references a Membership plan version that is not"
                        + " configured in tazzzo.membership.plans: planId=" + rule.planId() + " planVersion="
                        + rule.planVersion());
            }
        }
    }
}
