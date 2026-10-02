package com.tazzzo.customer.order;

import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.benefits.TransactionalBenefitsEvaluationPort;

/** Test doubles for the Benefits port the Order placement now composes. */
final class TestBenefits {

    /** The simplest valid evaluation: the customer has no Membership (the normal, non-failure outcome). */
    static final TransactionalBenefitsEvaluationPort NO_MEMBERSHIP = (session, customerId, subtotal) ->
            new BenefitEvaluation.NoBenefit(BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP);

    private TestBenefits() {
    }
}
