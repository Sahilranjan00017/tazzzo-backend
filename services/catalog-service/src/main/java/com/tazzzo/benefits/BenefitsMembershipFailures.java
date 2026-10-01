package com.tazzzo.benefits;

import com.tazzzo.membership.MembershipFailure;

/** Maps a Membership failure onto the closed Benefits vocabulary; never leaks {@code MembershipFailure}. */
final class BenefitsMembershipFailures {

    private BenefitsMembershipFailures() {
    }

    static BenefitsFailure map(MembershipFailure e) {
        BenefitsFailure.Reason reason = switch (e.reason()) {
            case INVALID_REQUEST -> BenefitsFailure.Reason.INVALID_REQUEST;
            case UNAVAILABLE -> BenefitsFailure.Reason.UNAVAILABLE;
            // INTEGRITY_FAILURE, and every reason an entitlement READ can never produce: unexplained => integrity
            default -> BenefitsFailure.Reason.INTEGRITY_FAILURE;
        };
        return new BenefitsFailure(reason, "membership entitlement read failed");
    }
}
