package com.tazzzo.membership;

import java.time.Instant;

/**
 * PR-16A-2 — the narrow answer a consumer (a future Benefits domain) gets when a customer currently has a
 * Membership entitlement: WHICH term proves it ({@code membershipId}), under WHICH immutable commercial plan
 * version ({@code planId}, {@code planVersion}), and until when ({@code validUntil}, exclusive).
 *
 * <p>Deliberately minimal. By existing, an entitlement means the Membership domain has already proven the
 * term is eligible at its own authoritative clock instant, so nothing that could be mistaken for mutable
 * state or a benefit is exposed: no customerId (the caller scopes by it), status, version, grant reference,
 * price, period, billing zone or timestamps. What an entitlement MEANS for a cart or order is a Benefits
 * decision, never Membership's.
 */
public record MembershipEntitlement(MembershipId membershipId, String planId, int planVersion, Instant validUntil) {

    public MembershipEntitlement {
        if (membershipId == null || planId == null || validUntil == null) {
            throw new IllegalArgumentException("entitlement field missing");
        }
        if (!MembershipPlan.PLAN_ID.matcher(planId).matches()) {
            throw new IllegalArgumentException("invalid plan id shape");
        }
        if (planVersion < 1) {
            throw new IllegalArgumentException("plan version must be >= 1");
        }
    }

    static MembershipEntitlement of(Membership term) {
        return new MembershipEntitlement(term.membershipId(), term.planId(), term.planVersion(), term.validUntil());
    }
}
