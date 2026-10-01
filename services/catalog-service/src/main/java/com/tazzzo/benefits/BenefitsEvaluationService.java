package com.tazzzo.benefits;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Money;
import com.tazzzo.membership.MembershipEntitlementPort;
import com.tazzzo.membership.MembershipFailure;
import org.springframework.stereotype.Component;

/**
 * The standalone Benefits evaluation ({@link BenefitsEvaluationPort}): asks Membership for the current entitlement
 * through {@link MembershipEntitlementPort}, then applies the exact-version rule. No transaction, no write. Records
 * only {@code benefits_failure{operation=evaluate,reason}}, after the outcome is known; a normal "no benefit"
 * records nothing.
 */
@Component
public class BenefitsEvaluationService implements BenefitsEvaluationPort {

    private final MembershipEntitlementPort membership;
    private final BenefitRuleSource rules;
    private final BenefitsObservability observability;

    public BenefitsEvaluationService(MembershipEntitlementPort membership, BenefitRuleSource rules,
                                     BenefitsObservability observability) {
        this.membership = membership;
        this.rules = rules;
        this.observability = observability;
    }

    @Override
    public BenefitEvaluation evaluate(CustomerId customerId, Money eligibleSubtotal) {
        try {
            BenefitEvaluator.requireInputs(customerId, eligibleSubtotal);
            try {
                return BenefitEvaluator.evaluate(membership.currentEntitlement(customerId), rules, eligibleSubtotal);
            } catch (MembershipFailure e) {
                throw BenefitsMembershipFailures.map(e);
            }
        } catch (BenefitsFailure e) {
            observability.failure(BenefitsObservability.Operation.EVALUATE, e.reason());
            throw e;
        }
    }
}
