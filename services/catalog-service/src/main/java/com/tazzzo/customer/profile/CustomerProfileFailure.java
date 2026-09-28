package com.tazzzo.customer.profile;

/**
 * PR-12A — every way a profile GET/PATCH can fail for reasons OWNED by this domain, as one typed
 * exception carrying a bounded, closed-vocabulary {@link Reason}. Authentication failures remain
 * {@code CustomerAuthFilter}'s exclusive concern (401 UNAUTHENTICATED / 503 SERVICE_UNAVAILABLE) —
 * this type never represents "who are you", only "your profile request itself is invalid, in
 * conflict, or the profile store is unavailable".
 */
public final class CustomerProfileFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST, PRECONDITION_REQUIRED, PRECONDITION_FAILED, UNAVAILABLE
    }

    private final Reason reason;

    public CustomerProfileFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
