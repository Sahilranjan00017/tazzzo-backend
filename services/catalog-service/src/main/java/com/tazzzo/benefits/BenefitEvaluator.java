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
        // EXACT (planId, planVersion): no fallback, ever
        Optional<BenefitRule> rule = rules.find(e.planId(), e.planVersion());
        if (rule.isEmpty()) {
            return new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_RULE);
        }
        BenefitRule r = rule.get();
        if (!r.admits(eligibleSubtotal)) {
            return new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE);
        }
        Money discount = r.discountBps().applyTo(eligibleSubtotal);
        // defense in depth only: BenefitRule guarantees >= 1 paise at its own threshold and the discount is monotonic
        // in the subtotal, so an admitted subtotal can never reach 0 paise for a constructed rule
        if (discount.paise() < 1) {
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
