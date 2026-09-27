package com.tazzzo.auth;

/**
 * PR-11A — every way customer-token verification can fail, as ONE typed exception carrying a
 * bounded, closed-vocabulary {@link Reason}. The HTTP layer ({@code CustomerAuthFilter}) collapses
 * ALL reasons to the SAME flat {@code 401 UNAUTHENTICATED} — a client learns nothing about WHICH
 * reason applied. The reason exists only for internal logs/metrics (never the raw token or a
 * message that could embed it).
 */
public final class CustomerAuthFailure extends RuntimeException {

    public enum Reason {
        MISSING, MALFORMED, INVALID_SIGNATURE, EXPIRED, FUTURE_ISSUED, NOT_READY, MALFORMED_CLAIMS
    }

    private final Reason reason;

    public CustomerAuthFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
