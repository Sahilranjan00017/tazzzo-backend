package com.tazzzo.membership;

import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PR-16A-1 — binds {@code tazzzo.membership.plans}. Binding only: every rule is enforced by
 * {@link MembershipPlan} and {@link ConfigBackedMembershipPlanSource} when the plan source bean is
 * created, which fails application startup. {@code MembershipService} never sees this class.
 */
@ConfigurationProperties(prefix = "tazzzo.membership")
public class MembershipPlanProperties {

    private List<Plan> plans = new ArrayList<>();

    public List<Plan> getPlans() {
        return plans;
    }

    public void setPlans(List<Plan> plans) {
        this.plans = plans;
    }

    List<MembershipPlan> toPlans() {
        List<MembershipPlan> result = new ArrayList<>(plans.size());
        for (Plan p : plans) {
            result.add(new MembershipPlan(p.planId, p.version,
                    new Money(p.pricePaise, Currency.valueOf(p.currency == null ? "" : p.currency)),
                    p.periodMonths, p.effectiveFrom, p.effectiveUntil));
        }
        return result;
    }

    public static class Plan {
        private String planId;
        private int version;
        private long pricePaise;
        private String currency;
        private int periodMonths;
        private Instant effectiveFrom;
        private Instant effectiveUntil;

        public String getPlanId() {
            return planId;
        }

        public void setPlanId(String planId) {
            this.planId = planId;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public long getPricePaise() {
            return pricePaise;
        }

        public void setPricePaise(long pricePaise) {
            this.pricePaise = pricePaise;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public int getPeriodMonths() {
            return periodMonths;
        }

        public void setPeriodMonths(int periodMonths) {
            this.periodMonths = periodMonths;
        }

        public Instant getEffectiveFrom() {
            return effectiveFrom;
        }

        public void setEffectiveFrom(Instant effectiveFrom) {
            this.effectiveFrom = effectiveFrom;
        }

        public Instant getEffectiveUntil() {
            return effectiveUntil;
        }

        public void setEffectiveUntil(Instant effectiveUntil) {
            this.effectiveUntil = effectiveUntil;
        }
    }
}
