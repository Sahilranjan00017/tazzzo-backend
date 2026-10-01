package com.tazzzo.benefits;

import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds {@code tazzzo.benefits.rules}. Binding only: every rule is enforced by {@link BenefitRule} and
 * {@link ConfigBackedBenefitRuleSource} when the rule source bean is created, which fails application startup.
 * The default is NO rules: no launch percentage or threshold is ratified.
 */
@ConfigurationProperties(prefix = "tazzzo.benefits")
public class BenefitsRuleProperties {

    private List<Rule> rules = new ArrayList<>();

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    List<BenefitRule> toRules() {
        List<BenefitRule> result = new ArrayList<>(rules.size());
        for (Rule r : rules) {
            result.add(new BenefitRule(r.planId, r.planVersion,
                    new Money(r.minimumSubtotalPaise, Currency.valueOf(r.currency == null ? "" : r.currency)),
                    new DiscountBps(r.discountBps)));
        }
        return result;
    }

    public static class Rule {
        private String planId;
        private int planVersion;
        private long minimumSubtotalPaise;
        private String currency;
        private int discountBps;

        public String getPlanId() {
            return planId;
        }

        public void setPlanId(String planId) {
            this.planId = planId;
        }

        public int getPlanVersion() {
            return planVersion;
        }

        public void setPlanVersion(int planVersion) {
            this.planVersion = planVersion;
        }

        public long getMinimumSubtotalPaise() {
            return minimumSubtotalPaise;
        }

        public void setMinimumSubtotalPaise(long minimumSubtotalPaise) {
            this.minimumSubtotalPaise = minimumSubtotalPaise;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public int getDiscountBps() {
            return discountBps;
        }

        public void setDiscountBps(int discountBps) {
            this.discountBps = discountBps;
        }
    }
}
