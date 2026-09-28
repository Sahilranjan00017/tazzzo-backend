package com.tazzzo.customer.profile;

/**
 * PR-12A — the flat public envelope for a profile-domain failure (never an authentication
 * failure — those remain {@code CustomerAuthErrorDto}'s shape, owned by {@code CustomerAuthFilter}).
 * Deliberately self-contained, mirroring {@code SessionErrorDto}/{@code OtpErrorEnvelope}'s
 * per-domain isolation rather than reusing a shared envelope type.
 */
public record CustomerProfileErrorDto(String code, String message, String requestId) {
}
