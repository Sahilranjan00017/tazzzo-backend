package com.tazzzo.membership;

/**
 * PR-16A-1 — every way a Membership operation can fail, as one typed exception carrying a closed
 * {@link Reason}. INTERNAL domain outcome: there is no HTTP surface, so nothing here is a public error
 * contract. Only reasons reachable in this PR exist.
 */
public final class MembershipFailure extends RuntimeException {

    public enum Reason {
        /** Malformed input, or an unknown plan / plan version. */
        INVALID_REQUEST,
        /** A known plan version that is not effective at the fresh Membership clock instant. */
        PLAN_NOT_ACTIVE,
        /** The customer's open term has not ended ({@code now < validUntil}). */
        ALREADY_ACTIVE,
        /** The grant reference already exists with different semantic input. */
        GRANT_REF_CONFLICT,
        /** A corrupt persisted row, or a guarded write that cannot be explained by durable state. */
        INTEGRITY_FAILURE,
        /** PR-16A-3: no current/open term to cancel, or no term with the revoked id. */
        NOT_FOUND,
        /** PR-16A-3: the term is not in a state this command can act on (time-ended or terminal). */
        INVALID_TRANSITION,
        UNAVAILABLE
    }

    private final Reason reason;

    public MembershipFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
