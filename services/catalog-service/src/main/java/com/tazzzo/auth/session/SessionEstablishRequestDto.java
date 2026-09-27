package com.tazzzo.auth.session;

/** PR-11C — {@code POST /v1/auth/session} body. */
public record SessionEstablishRequestDto(String grantId) {
}
