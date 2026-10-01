package com.tazzzo.membership;

/**
 * The persisted lifecycle state of one Membership term. Exactly the states that have a producer:
 * <ul>
 *   <li>{@link #ACTIVE}: produced by {@code MembershipService.grant}.</li>
 *   <li>{@link #EXPIRED}: produced ONLY by the lazy expiration a later grant performs on a customer's
 *       time-ended open term.</li>
 *   <li>{@link #REVOKED} (PR-16A-3): produced ONLY by {@code MembershipTerminationService.revoke}, the
 *       immediate administrative termination. Terminal and never an entitlement; it carries no
 *       {@code openTerm} marker (the marker is $unset by the revoke CAS, never set to false).</li>
 * </ul>
 * No other constant exists until an operation produces it. <b>Persisted state is not runtime entitlement
 * truth:</b> a persisted {@code ACTIVE} row whose {@code validUntil} has passed is valid stale state
 * (non-entitling, not corruption) that a later grant lazily moves to {@code EXPIRED}. Cancel-at-period-end is
 * NOT a status: it is the {@code cancelRequestedAt} fact on an {@code ACTIVE} term, whose entitlement continues
 * until {@code validUntil}.
 */
public enum MembershipStatus {
    ACTIVE, EXPIRED, REVOKED
}
