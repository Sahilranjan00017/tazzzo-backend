package com.tazzzo.content;

/** A refused content/config operation. Generic messages only. */
public final class ContentFailure extends RuntimeException {

    public enum Reason { INVALID, NOT_FOUND, STALE_VERSION, STATE_CONFLICT, STORAGE_UNAVAILABLE, STORAGE_OUTAGE }

    private final Reason reason;

    public ContentFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
