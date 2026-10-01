package com.tazzzo.benefits;

/**
 * Every way a Benefits evaluation can FAIL, as one typed exception carrying a closed {@link Reason}. INTERNAL
 * domain outcome (no HTTP surface). A normal "no benefit" is a result, never a failure; an infrastructure or
 * integrity problem is never turned into one.
 */
public final class BenefitsFailure extends RuntimeException {

    public enum Reason {
        /** A missing or malformed argument (including the Membership domain rejecting the customer id). */
        INVALID_REQUEST,
        /** The Membership datastore could not answer. */
        UNAVAILABLE,
        /** Membership reported corrupt persisted state (or an outcome that cannot occur on an entitlement read). */
        INTEGRITY_FAILURE,
        /** The Benefits rule source itself failed. */
        RULE_CONFIGURATION_FAILURE
    }

    private final Reason reason;

    public BenefitsFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
