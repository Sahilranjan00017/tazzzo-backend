package com.tazzzo.serviceability;

/** An update addressed a service-area config that does not exist. */
public class ServiceabilityNotFoundException extends ServiceabilityException {
    public ServiceabilityNotFoundException(String message) { super(message); }
}
