package com.tazzzo.membership;

/**
 * PR-16A-1 — the persisted lifecycle state of one Membership term. Exactly the states that have a
 * producer today:
 * <ul>
 *   <li>{@link #ACTIVE}: produced by {@code MembershipService.grant}.</li>
 *   <li>{@link #EXPIRED}: produced ONLY by the lazy expiration a later grant performs on a customer's
 *       time-expired open term.</li>
 * </ul>
 * No other constant exists until an operation produces it. <b>Persisted state is not runtime
 * entitlement truth:</b> a persisted {@code ACTIVE} row whose {@code validUntil} has passed is valid
 * stale state (non-entitling, not corruption) that a later grant lazily moves to {@code EXPIRED}.
 */
public enum MembershipStatus {
    ACTIVE, EXPIRED
}
