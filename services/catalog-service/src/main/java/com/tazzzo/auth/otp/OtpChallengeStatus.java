package com.tazzzo.auth.otp;

/**
 * PR-11B — the OTP challenge state machine. Transitions are one-way; nothing here ever moves
 * backward.
 *
 * <pre>
 *   PENDING_DELIVERY -&gt; ACTIVE            provider send succeeded
 *   PENDING_DELIVERY -&gt; DELIVERY_FAILED   provider send failed/timed out; slot freed for retry
 *   ACTIVE           -&gt; VERIFIED          correct OTP, exactly once (atomic CAS)
 *   ACTIVE           -&gt; LOCKED            attempt count reached the configured maximum
 *   ACTIVE           -&gt; EXPIRED           enforced by application logic against expiresAt, never
 *                                          only by the Mongo TTL sweep (which is asynchronous)
 *   ACTIVE           -&gt; SUPERSEDED        a resend created a new challenge for the same phone
 * </pre>
 *
 * <p>{@code VERIFIED} is terminal for the CHALLENGE. It is NOT the same thing as the one-time login
 * grant being consumed — that is a separate lifecycle on {@code VerifiedOtpGrant}, owned by PR-11C.
 */
public enum OtpChallengeStatus {
    PENDING_DELIVERY,
    ACTIVE,
    VERIFIED,
    LOCKED,
    EXPIRED,
    SUPERSEDED,
    DELIVERY_FAILED
}
