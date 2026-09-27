package com.tazzzo.auth.session;

/**
 * PR-11C — flat public envelope for session-establishment/refresh failures, deliberately
 * independent of the frozen {@code commerce.contract.PublicErrorCode} (ADR-010) and of
 * {@code OtpErrorDto}/{@code CustomerAuthErrorDto} (different trust domains). A small, stable
 * vocabulary: {@code INVALID_REQUEST}, {@code UNAUTHENTICATED}, {@code SERVICE_UNAVAILABLE}.
 */
public record SessionErrorDto(String code, String message, String requestId) {
}
