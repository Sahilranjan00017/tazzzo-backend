package com.tazzzo.auth.session;

/** PR-11C — {@code POST /v1/auth/refresh} body. */
public record RefreshRequestDto(String refreshToken) {
}
