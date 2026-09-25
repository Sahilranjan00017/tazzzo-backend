package com.tazzzo.serviceability;

/** Base for typed serviceability failures. Messages are internal. */
public abstract class ServiceabilityException extends RuntimeException {
    protected ServiceabilityException(String message) { super(message); }
}
