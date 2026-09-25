package com.tazzzo.catalog.ratelimit;

import java.util.List;

/**
 * Shared limiter state. The only implementation that may exist in production is distributed
 * (Q5-MECH-1): two catalogue tasks serve consumers concurrently, so per-instance state would make
 * the effective allowance depend on the replica count.
 *
 * <p><b>There is deliberately no in-memory implementation.</b> Not as a fallback, not as a
 * convenience — a per-process bucket that silently replaced the shared one would be the exact
 * failure Q5-MECH-1 rejects, and it would be invisible.
 */
public interface RateLimitStore {

    /**
     * Charges {@code cost} against every bucket, ALL-OR-NOTHING (Q5-ATOMIC-1).
     *
     * <p>If every bucket has capacity, all are debited atomically and the result is
     * {@link Admission.Allowed}. If ANY bucket cannot pay, NONE is debited — otherwise a failing
     * installation bucket would silently drain the shared IP bucket on every rejected request,
     * which is a denial-of-service amplifier rather than a limiter.
     *
     * @param cost the explicit weight of this request; never inferred here
     */
    Admission tryConsume(List<BucketSpec> buckets, int cost);
}
