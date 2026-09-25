package com.tazzzo.serviceability;

/** Optimistic-concurrency conflict: stale expected version, or duplicate canonical create. */
public class ServiceabilityConflictException extends ServiceabilityException {
    public ServiceabilityConflictException(String message) { super(message); }
}
