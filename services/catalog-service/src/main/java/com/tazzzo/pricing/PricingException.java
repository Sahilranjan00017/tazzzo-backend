package com.tazzzo.pricing;

/** Base for typed pricing failures. Messages are internal; never surfaced to public clients here. */
public abstract class PricingException extends RuntimeException {
    protected PricingException(String message) { super(message); }
}
