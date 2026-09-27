package com.tazzzo.auth.otp;

/**
 * PR-11B — flat public envelope for OTP failures, deliberately independent of the frozen
 * {@code commerce.contract.PublicErrorCode} (ADR-010) and of {@code CustomerAuthErrorDto} (a
 * different trust domain). A small, stable vocabulary: {@code OTP_INVALID_REQUEST},
 * {@code OTP_INVALID}, {@code OTP_EXPIRED}, {@code OTP_RATE_LIMITED}, {@code SERVICE_UNAVAILABLE}.
 */
public record OtpErrorDto(String code, String message, String requestId, Integer retryAfterSeconds) {
}
