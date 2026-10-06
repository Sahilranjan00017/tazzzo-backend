package com.tazzzo.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-customer budgets for the customer-authenticated surface ({@code /v1/customer/**}). Reads and writes are separate
 * buckets so a burst of polling can never starve checkout. Both unset (capacity 0) = not enforced (the startup log says so);
 * partially configured = startup failure. Values are a deployment decision; the test profile carries fixtures.
 */
@ConfigurationProperties(prefix = "tazzzo.customer-rate-limit")
public class CustomerRateLimitProperties {

    public static class Bucket {
        private long capacity;
        private double refillPerSecond;

        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }
        public double getRefillPerSecond() { return refillPerSecond; }
        public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }

        boolean configured() {
            return capacity > 0 && refillPerSecond > 0;
        }

        boolean untouched() {
            return capacity == 0 && refillPerSecond == 0;
        }
    }

    private Bucket reads = new Bucket();
    private Bucket writes = new Bucket();

    public Bucket getReads() { return reads; }
    public void setReads(Bucket reads) { this.reads = reads; }
    public Bucket getWrites() { return writes; }
    public void setWrites(Bucket writes) { this.writes = writes; }

    /** @return true when enforcement is configured; false when entirely unset. @throws IllegalStateException partial/invalid */
    public boolean resolveEnabled() {
        if (reads.untouched() && writes.untouched()) {
            return false;
        }
        if (!reads.configured() || !writes.configured() || reads.getCapacity() > 1_000_000 || writes.getCapacity() > 1_000_000) {
            throw new IllegalStateException("tazzzo.customer-rate-limit.{reads,writes}.{capacity,refill-per-second} must all be positive "
                    + "(capacity at most 1000000) or all unset");
        }
        return true;
    }
}
