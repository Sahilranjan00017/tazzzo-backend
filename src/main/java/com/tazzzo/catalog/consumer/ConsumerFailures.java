package com.tazzzo.catalog.consumer;

import java.time.Duration;

/**
 * The three consumer failure kinds, each mapped to exactly one status by
 * {@link ConsumerExceptionHandler}. Kept as distinct types so a call site cannot accidentally
 * report an infrastructure fault as throttling, or a hidden node as an error.
 */
public final class ConsumerFailures {

    private ConsumerFailures() {
    }

    /**
     * 404. Deliberately indistinguishable across "absent from this release", "not active" and
     * "consumer-empty" (L-5): a shopper, and anyone probing, learns only that it is not there.
     */
    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /**
     * 503. The service could not DECIDE — limiter unavailable or disabled, client IP unresolvable,
     * broken release pointer, snapshot or probe failure. Never 429: a store outage is not "you
     * exceeded your rate", and telling a client to back off from an infrastructure fault teaches it
     * to wait for something waiting cannot fix.
     */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** 429 + Retry-After. An actual bucket denial, and the only thing that earns this status. */
    public static class RateLimited extends RuntimeException {
        private final Duration retryAfter;

        public RateLimited(Duration retryAfter) {
            super("rate limited");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}
