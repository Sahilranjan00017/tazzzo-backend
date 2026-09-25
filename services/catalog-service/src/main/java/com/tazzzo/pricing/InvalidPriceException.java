package com.tazzzo.pricing;

/** A price command violated a domain invariant (bounds, currency, window). */
public class InvalidPriceException extends PricingException {
    public InvalidPriceException(String message) { super(message); }
}
