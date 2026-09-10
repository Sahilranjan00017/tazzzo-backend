package com.tazzzo.catalog.ratelimit;

import java.time.Duration;

/**
 * The limiter's answer. Three outcomes, deliberately distinct (Q5-FAIL-1):
 *
 * <pre>
 *   ALLOWED       every applicable bucket paid the cost
 *   RATE_LIMITED  a bucket could not pay        -> 429 + Retry-After
 *   UNAVAILABLE   the limiter could not decide  -> 503, NEVER 429
 * </pre>
 *
 * <p><b>A store outage is not "you exceeded your rate".</b> Returning 429 for infrastructure
 * failure would be semantically false and would teach clients to back off from a problem that
 * backing off cannot fix.
 */
public sealed interface Admission {

    record Allowed() implements Admission { }

    /** @param retryAfter time until EVERY required bucket could admit — max over the deficient ones */
    record RateLimited(Duration retryAfter) implements Admission { }

    record Unavailable(String reason) implements Admission { }

    static Admission allowed() {
        return new Allowed();
    }
}
