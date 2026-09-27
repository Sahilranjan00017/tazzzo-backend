package com.tazzzo.auth.session;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PR-11C — session/refresh policy configuration. The refresh-token signing secret has NO default
 * (mirrors {@code CustomerAuthProperties}/{@code OtpAuthProperties} — production values are a
 * deployment secret, never invented here) and is a SEPARATE key domain from both the access-token
 * signing key (PR-11A) and the OTP verifier/phone-digest key (PR-11B) — no key reuse across trust
 * boundaries. TTLs carry explicit production-target defaults, validated at startup.
 */
@ConfigurationProperties(prefix = "tazzzo.customer-auth.session")
public class CustomerSessionProperties {

    /** Base64 of >= 32 random bytes. Signs the refresh-token verifier ONLY. */
    private String refreshTokenHmacKeyB64;

    private long accessTokenTtlSeconds = 900;
    private long sessionTtlSeconds = 2_592_000; // 30 days

    public String getRefreshTokenHmacKeyB64() {
        return refreshTokenHmacKeyB64;
    }

    public void setRefreshTokenHmacKeyB64(String refreshTokenHmacKeyB64) {
        this.refreshTokenHmacKeyB64 = refreshTokenHmacKeyB64;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getSessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public void setSessionTtlSeconds(long sessionTtlSeconds) {
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    /** Invalid policy must never start silently. */
    @PostConstruct
    void validate() {
        if (accessTokenTtlSeconds <= 0) {
            throw new IllegalStateException("tazzzo.customer-auth.session.access-token-ttl-seconds must be > 0");
        }
        if (sessionTtlSeconds <= 0) {
            throw new IllegalStateException("tazzzo.customer-auth.session.session-ttl-seconds must be > 0");
        }
        if (accessTokenTtlSeconds >= sessionTtlSeconds) {
            throw new IllegalStateException(
                    "tazzzo.customer-auth.session.access-token-ttl-seconds must be shorter than session-ttl-seconds");
        }
    }
}
