package com.tazzzo.catalog.ratelimit;

/**
 * What the store OBSERVED about one bucket in the same atomic execution that produced the
 * admission decision (Q5-OBS-1).
 *
 * <pre>
 *   ALLOWED       remaining = post-debit token count
 *   RATE_LIMITED  remaining = tokens available at decision time; nothing was debited
 * </pre>
 *
 * <p>It is derived from the SAME Lua execution as the verdict — never from a second Redis read,
 * because by then another request could have changed the bucket and the metric would describe a
 * state that did not produce the decision. Carries the dimension and numbers only: no key, no IP,
 * no installation id.
 */
public record BucketObservation(BucketDimension dimension, long capacity, double remaining) {

    public BucketObservation {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension is required");
        }
    }

    /** {@code 1 - remaining/capacity}, clamped to [0, 1]. */
    public double saturation() {
        if (capacity <= 0) {
            return 1.0;
        }
        double s = 1.0 - (remaining / (double) capacity);
        return Math.max(0.0, Math.min(1.0, s));
    }
}
