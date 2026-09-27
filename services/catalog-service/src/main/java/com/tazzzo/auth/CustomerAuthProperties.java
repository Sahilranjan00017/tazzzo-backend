package com.tazzzo.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PR-11A signing configuration. {@code tazzzo.customer-auth.access-token-hmac-key-b64} — the
 * Base64 of at least 32 random bytes. NO DEFAULT BY DESIGN (mirrors {@code ConsumerCursorProperties}):
 * the production value is a deployment secret, never invented here, never checked in. When it is
 * missing or malformed, {@link CustomerAccessTokenCodec} is NOT READY and every customer-authenticated
 * request fails closed with 401 — there is no anonymous fallback for this surface.
 */
@ConfigurationProperties(prefix = "tazzzo.customer-auth")
public class CustomerAuthProperties {

    private String accessTokenHmacKeyB64;

    public String getAccessTokenHmacKeyB64() {
        return accessTokenHmacKeyB64;
    }

    public void setAccessTokenHmacKeyB64(String accessTokenHmacKeyB64) {
        this.accessTokenHmacKeyB64 = accessTokenHmacKeyB64;
    }
}
