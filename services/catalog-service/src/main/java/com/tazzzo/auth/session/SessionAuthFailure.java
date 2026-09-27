package com.tazzzo.auth.session;

/**
 * PR-11C — every way session establishment/refresh can fail, as ONE typed exception carrying a
 * bounded closed-vocabulary {@link Reason}. Mirrors {@code OtpFailure}/{@code CustomerAuthFailure}:
 * the HTTP layer collapses grant/refresh-token problems (unknown, consumed, expired, wrong purpose,
 * digest mismatch, revoked, rotated-away) to the SAME generic {@code INVALID} outcome so a caller
 * cannot enumerate internal state.
 */
public final class SessionAuthFailure extends RuntimeException {

    public enum Reason {
        /** Malformed request shape (bad grantId/refreshToken shape) — a client bug, not an auth outcome. */
        INVALID_REQUEST,
        /** Unknown/consumed/expired/wrong-purpose grant, or unknown/expired/revoked/rotated-away/
         *  digest-mismatched refresh token — all collapsed to the SAME public outcome. */
        INVALID,
        /** Codec/dependency not configured, or a transient persistence failure. */
        UNAVAILABLE
    }

    private final Reason reason;

    public SessionAuthFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
