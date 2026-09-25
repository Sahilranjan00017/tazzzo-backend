package com.tazzzo.serviceability;

/** A serviceability command or config violated a domain invariant. */
public class InvalidServiceabilityException extends ServiceabilityException {
    public InvalidServiceabilityException(String message) { super(message); }
}
