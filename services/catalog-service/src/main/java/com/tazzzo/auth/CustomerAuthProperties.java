package com.tazzzo.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * PR-11A signing configuration. {@code tazzzo.customer-auth.access-token-hmac-key-b64} — the
 * Base64 of at least 32 random bytes. NO DEFAULT BY DESIGN (mirrors {@code ConsumerCursorProperties}):
 * the production value is a deployment secret, never invented here, never checked in. When it is
 * missing or malformed, {@link CustomerAccessTokenCodec} is NOT READY and every customer-authenticated
 * request fails closed with 401 — there is no anonymous fallback for this surface.
 *
 * <p><b>PR-11C key rotation:</b> {@code tazzzo.customer-auth.previous-access-token-hmac-keys-b64} —
 * a comma-separated list of PREVIOUS signing keys, VERIFICATION-ONLY. During a rotation window, new
 * tokens are always signed with the current key; tokens signed with a previous key still verify
 * until it is removed from this list. No wire-format change: the token never carries a key id, so
 * verification tries the current key, then each previous key, in order.
 */
@ConfigurationProperties(prefix = "tazzzo.customer-auth")
public class CustomerAuthProperties {

    private String accessTokenHmacKeyB64;
    private List<String> previousAccessTokenHmacKeysB64 = new ArrayList<>();

    public String getAccessTokenHmacKeyB64() {
        return accessTokenHmacKeyB64;
    }

    public void setAccessTokenHmacKeyB64(String accessTokenHmacKeyB64) {
        this.accessTokenHmacKeyB64 = accessTokenHmacKeyB64;
    }

    public List<String> getPreviousAccessTokenHmacKeysB64() {
        return previousAccessTokenHmacKeysB64;
    }

    public void setPreviousAccessTokenHmacKeysB64(List<String> previousAccessTokenHmacKeysB64) {
        this.previousAccessTokenHmacKeysB64 = previousAccessTokenHmacKeysB64;
    }
}
