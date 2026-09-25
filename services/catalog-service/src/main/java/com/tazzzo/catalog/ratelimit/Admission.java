package com.tazzzo.catalog.ratelimit;

import java.time.Duration;
import java.util.List;

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

    /** @param observations per-bucket state from the SAME execution that admitted (Q5-OBS-1) */
    record Allowed(List<BucketObservation> observations) implements Admission { }

    /**
     * @param retryAfter time until EVERY required bucket could admit — max over the deficient ones
     * @param observations per-bucket state at decision time; nothing was debited
     */
    record RateLimited(Duration retryAfter, List<BucketObservation> observations) implements Admission { }

    /** No observations: a fabricated remaining/saturation for a decision that was never made is a lie. */
    record Unavailable(String reason) implements Admission { }
}
