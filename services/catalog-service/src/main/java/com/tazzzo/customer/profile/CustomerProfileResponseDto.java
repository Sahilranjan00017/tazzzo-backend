package com.tazzzo.customer.profile;

/**
 * PR-12A — the public profile projection. Deliberately excludes {@code phoneNormalized} (auth
 * identity data, never re-exposed here) and any Mongo internals beyond the public {@code version}
 * counter.
 */
public record CustomerProfileResponseDto(String customerId, String displayName, String email, long version,
                                          String requestId) {
}
