package com.tazzzo.auth;

/**
 * PR-11A — the flat public envelope for a customer-authentication failure, deliberately
 * self-contained (never reusing {@code commerce.api}'s {@code ErrorEnvelopeDto} or the FROZEN
 * {@code commerce.contract.PublicErrorCode} — ADR-010 explicitly forbids adding speculative values
 * to that enum, and {@code auth} must not depend on {@code commerce.api} transport types). Every
 * authentication failure — missing header, malformed bearer, bad signature, expired token, unknown
 * session — flattens to the SAME shape: {@code code=UNAUTHENTICATED}, a generic message, and the
 * server-authoritative request id. Nothing here ever carries the raw token or an exception message.
 */
public record CustomerAuthErrorDto(String code, String message, String requestId) {
}
