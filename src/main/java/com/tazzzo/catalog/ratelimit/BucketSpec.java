package com.tazzzo.catalog.ratelimit;

/**
 * One token bucket: its key and its shape (Q5-c token-bucket semantics — capacity is the burst
 * allowance, refill rate is the sustained allowance).
 *
 * <p>The numbers are NEVER chosen in code. They are mandatory production configuration derived from
 * load measurement; test profiles carry fixture values that are not production recommendations.
 */
public record BucketSpec(String key, long capacity, double refillPerSecond) {

    public BucketSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("bucket key is required");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("bucket capacity must be positive: " + key);
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("bucket refill rate must be positive: " + key);
        }
    }
}
