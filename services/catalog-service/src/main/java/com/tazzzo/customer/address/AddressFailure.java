package com.tazzzo.customer.address;

/**
 * PR-12B — every way an address GET/POST/PATCH/DELETE/set-default can fail for reasons OWNED by
 * this domain, as one typed exception carrying a bounded, closed-vocabulary {@link Reason}.
 * Authentication failures remain {@code CustomerAuthFilter}'s exclusive concern (401
 * UNAUTHENTICATED) — this type never represents "who are you", only "your address request is
 * invalid, unknown/not yours, in conflict, at capacity, or the address store is unavailable".
 */
public final class AddressFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST, NOT_FOUND, PRECONDITION_REQUIRED, PRECONDITION_FAILED, ADDRESS_LIMIT_REACHED,
        IDEMPOTENCY_CONFLICT, UNAVAILABLE
    }

    private final Reason reason;

    public AddressFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
