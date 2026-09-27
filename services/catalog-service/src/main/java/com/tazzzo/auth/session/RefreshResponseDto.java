package com.tazzzo.auth.session;

/** PR-11C — {@code POST /v1/auth/refresh} success response. A NEW refresh token every time — the
 *  old one is rotated away and never usable again. */
public record RefreshResponseDto(String accessToken, long accessTokenExpiresIn, String refreshToken,
                                 String requestId) {
}
