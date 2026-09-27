package com.tazzzo.auth.session;

/**
 * PR-11C — {@code sessionId} is DELIBERATELY OMITTED from this public response (a design decision,
 * not an oversight): the client never needs it directly — the access token already carries it
 * internally, and the refresh token embeds it as an opaque routing prefix — so there is no reason
 * to hand a raw internal identifier to the client when omitting it costs nothing. {@code
 * phoneNormalized} is likewise never returned.
 */
public record SessionEstablishResponseDto(String customerId, String accessToken, long accessTokenExpiresIn,
                                          String refreshToken, String requestId) {
}
