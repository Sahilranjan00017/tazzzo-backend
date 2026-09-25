package com.tazzzo.pricing;

/** Optimistic-concurrency conflict: the expected version did not match current state. */
public class PriceConflictException extends PricingException {
    public PriceConflictException(String message) { super(message); }
}
