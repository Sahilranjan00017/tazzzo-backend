package com.tazzzo.benefits;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Money;
import com.tazzzo.membership.MembershipFailure;
import com.tazzzo.membership.TransactionalMembershipEntitlementPort;
import org.springframework.stereotype.Component;

/**
 * The session-aware Benefits evaluation ({@link TransactionalBenefitsEvaluationPort}). Structurally incapable of
 * what its port forbids: it has no {@code Tx} (cannot open a transaction), no observability or registry (cannot
 * emit a metric), no standalone Membership port (cannot fall back) and no persistence. It joins the caller's
 * session through {@link TransactionalMembershipEntitlementPort}. A transient driver error is NOT a
 * {@code MembershipFailure} and so propagates untouched for the caller's own retry.
 */
@Component
public class BenefitsTransactionalEvaluator implements TransactionalBenefitsEvaluationPort {

    private final TransactionalMembershipEntitlementPort membership;
    private final BenefitRuleSource rules;

    public BenefitsTransactionalEvaluator(TransactionalMembershipEntitlementPort membership, BenefitRuleSource rules) {
        this.membership = membership;
        this.rules = rules;
    }

    @Override
    public BenefitEvaluation evaluate(ClientSession session, CustomerId customerId, Money eligibleSubtotal) {
        if (session == null) {
            throw new IllegalArgumentException("session required");
        }
        BenefitEvaluator.requireInputs(customerId, eligibleSubtotal);
        try {
            return BenefitEvaluator.evaluate(membership.currentEntitlement(session, customerId), rules,
                    eligibleSubtotal);
        } catch (MembershipFailure e) {
            throw BenefitsMembershipFailures.map(e);
        }
    }
}
