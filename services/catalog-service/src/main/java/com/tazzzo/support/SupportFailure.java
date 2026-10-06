package com.tazzzo.support;

/** A refused support operation; the message is deliberately generic. */
public final class SupportFailure extends RuntimeException {

    public enum Reason { INVALID_REQUEST, NOT_FOUND, STATE_CONFLICT, STALE_VERSION, TOO_MANY_OPEN, MESSAGE_LIMIT, UNAVAILABLE }

    private final Reason reason;

    public SupportFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
