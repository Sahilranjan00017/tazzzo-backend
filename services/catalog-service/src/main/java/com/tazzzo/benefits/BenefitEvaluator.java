package com.tazzzo.benefits;

import com.tazzzo.common.money.Money;
import com.tazzzo.membership.MembershipEntitlement;

import java.util.Optional;

/**
 * The ONE deterministic Benefits decision, shared by the standalone and transactional services. Pure: it reads the
 * entitlement Membership already proved (it never inspects Membership lifecycle state) and the exact-version rule.
 */
final class BenefitEvaluator {

    private BenefitEvaluator() {
    }

    static BenefitEvaluation evaluate(Optional<MembershipEntitlement> entitlement, BenefitRuleSource rules,
                                      Money eligibleSubtotal) {
        if (entitlement.isEmpty()) {
            return new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP);
        }
        MembershipEntitlement e = entitlement.get();
        Optional<BenefitRule> rule;
        try {
            rule = rules.find(e.planId(), e.planVersion()); // EXACT (planId, planVersion): no fallback, ever
        } catch (RuntimeException ex) {
            throw new BenefitsFailure(BenefitsFailure.Reason.RULE_CONFIGURATION_FAILURE, "benefit rule lookup failed");
        }
        if (rule.isEmpty()) {
            return new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE);
        }
        BenefitRule r = rule.get();
        if (!r.admits(eligibleSubtotal)) {
            return new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE);
        }
        Money discount = r.discountBps().applyTo(eligibleSubtotal);
        if (discount.paise() < 1) { // floor rounded a tiny subtotal to nothing: not a real discount
            return new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE);
        }
        return new BenefitEvaluation.Applied(e.membershipId(), e.planId(), e.planVersion(), eligibleSubtotal,
                discount, r.discountBps());
    }

    static void requireInputs(Object customerId, Money eligibleSubtotal) {
        if (customerId == null) {
            throw new BenefitsFailure(BenefitsFailure.Reason.INVALID_REQUEST, "customerId required");
        }
        if (eligibleSubtotal == null) {
            throw new BenefitsFailure(BenefitsFailure.Reason.INVALID_REQUEST, "eligibleSubtotal required");
        }
    }
}
