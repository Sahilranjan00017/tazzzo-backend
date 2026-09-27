package com.tazzzo.auth.otp;

import java.time.Duration;

/**
 * PR-11B — every way an OTP request/verify can fail, as ONE typed exception carrying a bounded
 * closed-vocabulary {@link Reason}. Mirrors {@code CustomerAuthFailure}'s pattern: the HTTP layer
 * ({@code OtpExceptionHandler}) maps each reason to one of a SMALL, STABLE set of public codes —
 * never the raw internal detail (which Mongo path failed, whether a challenge existed at all, how
 * many attempts remain).
 *
 * <p><b>{@link Reason#EXPIRED} is deliberately distinguished from {@link Reason#INVALID} at the
 * public boundary</b> — this is the ONE enumeration exception in an otherwise fully collapsed
 * vocabulary (unknown/wrong/locked/superseded/already-verified are all {@code INVALID}). It is a
 * conscious UX/security tradeoff: telling a legitimate user "this code expired, request a new one"
 * versus "that code is wrong" measurably improves the resend flow, and the information leaked — that
 * SOME challenge existed and outlived its TTL — is not attacker-actionable (it reveals nothing about
 * the OTP value, the phone's customer status, or remaining attempts). If that tradeoff is ever
 * revisited, collapsing EXPIRED into INVALID is a one-line change in {@code OtpExceptionHandler}.
 */
public final class OtpFailure extends RuntimeException {

    public enum Reason {
        /** Malformed phone/OTP/challengeId shape — a client bug, not an auth outcome. */
        INVALID_REQUEST,
        /** Wrong OTP, unknown challenge, locked challenge, or superseded challenge — all collapsed
         *  to the SAME public outcome so a caller cannot distinguish "doesn't exist" from "wrong". */
        INVALID,
        /** The challenge existed and was active, but its expiry has passed. */
        EXPIRED,
        /** A rate-limit bucket denied the request. */
        RATE_LIMITED,
        /** Codec/provider/rate-limiter not configured, or provider delivery failed. */
        UNAVAILABLE
    }

    private final Reason reason;
    private final Duration retryAfter;

    public OtpFailure(Reason reason) {
        this(reason, null);
    }

    public OtpFailure(Reason reason, Duration retryAfter) {
        super(reason.name());
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    public Reason reason() {
        return reason;
    }

    /** Populated only for {@link Reason#RATE_LIMITED}. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
