package com.tazzzo.benefits;

import com.tazzzo.common.money.Money;
import com.tazzzo.membership.MembershipId;

/**
 * The immutable outcome of one Benefits evaluation: either {@link NoBenefit} (normal, never an error) or
 * {@link Applied}, which carries exactly the authority a future Checkout money snapshot needs to persist without
 * re-running rules: the term that proved eligibility, the exact plan snapshot, the subtotal the rule was applied
 * to, the rate and the resulting discount.
 */
public sealed interface BenefitEvaluation {

    /** Why nothing applies. Closed; each is a normal outcome. */
    enum NoBenefitReason {
        /** The customer has no current Membership entitlement. */
        NO_MEMBERSHIP,
        /** An entitlement exists but no rule is configured for its exact (planId, planVersion). */
        NO_RULE,
        /** A rule exists but the eligible subtotal is below its minimum (or the floor discount is zero paise). */
        NOT_ELIGIBLE
    }

    record NoBenefit(NoBenefitReason reason) implements BenefitEvaluation {
        public NoBenefit {
            if (reason == null) {
                throw new IllegalArgumentException("reason required");
            }
        }
    }

    record Applied(MembershipId membershipId, String planId, int planVersion, Money eligibleSubtotal,
                   Money discountAmount, DiscountBps discountBps) implements BenefitEvaluation {
        public Applied {
            if (membershipId == null || planId == null || eligibleSubtotal == null || discountAmount == null
                    || discountBps == null) {
                throw new IllegalArgumentException("applied benefit field missing");
            }
            if (planVersion < 1) {
                throw new IllegalArgumentException("plan version must be >= 1");
            }
            if (discountAmount.paise() < 1) {
                throw new IllegalArgumentException("an applied benefit discounts at least one paise");
            }
            if (discountAmount.currency() != eligibleSubtotal.currency()
                    || discountAmount.paise() > eligibleSubtotal.paise()) {
                throw new IllegalArgumentException("a discount can never exceed its eligible subtotal");
            }
            if (!discountAmount.equals(discountBps.applyTo(eligibleSubtotal))) {
                throw new IllegalArgumentException("discount does not equal floor(subtotal * bps / 10000)");
            }
        }
    }
}
