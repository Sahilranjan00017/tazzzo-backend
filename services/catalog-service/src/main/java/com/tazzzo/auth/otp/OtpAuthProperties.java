package com.tazzzo.auth.otp;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PR-11B — OTP policy configuration. The signing secret has NO default (mirrors
 * {@code CustomerAuthProperties}/{@code ConsumerCursorProperties} — production values are a
 * deployment secret, never invented here). Lifetime/cooldown/attempt policy DOES carry an explicit
 * production-target default (matches the {@code tazzzo.scheduler.*} operational-tuning convention),
 * validated at startup by {@link OtpAuthConfig} rather than left to fail lazily on first use.
 */
@ConfigurationProperties(prefix = "tazzzo.customer-auth.otp")
public class OtpAuthProperties {

    /** Base64 of >= 32 random bytes. Separate from the access-token signing key by design. */
    private String hmacKeyB64;

    private long ttlSeconds = 300;
    private long resendCooldownSeconds = 30;
    private int maxAttempts = 5;
    private long grantTtlSeconds = 300;
    /**
     * Hardening pass (durability §6/§7) — the bound on how long a PENDING_DELIVERY challenge may
     * hold the "delivering" slot before it is considered stale (e.g. the process crashed between
     * inserting it and recording the provider outcome). Deliberately SHORT and independent of
     * {@link #ttlSeconds} (the OTP's own validity window, which starts at CONFIRMED delivery, not
     * at challenge creation — see {@code OtpService#createAndDeliver}).
     */
    private long deliveryTimeoutSeconds = 60;

    /**
     * NO default. Empty/unset means no {@code OtpDeliveryProvider} bean is wired, so the OTP
     * request endpoint fails closed with 503 rather than silently discarding an OTP. {@code
     * LOGGING} wires the dev-only {@link LoggingOtpDeliveryProvider}; there is deliberately no
     * production provider shipped in this PR. {@code HTTP} wires the generic HTTPS gateway adapter
     * ({@link HttpOtpDeliveryProvider}), validated fail-closed at startup.
     */
    private String providerMode;

    /** Settings of the HTTPS gateway adapter; only read when {@code provider-mode=HTTP}. */
    private HttpOtpGatewayProperties http = new HttpOtpGatewayProperties();

    private Bucket requestIp = new Bucket();
    private Bucket requestPhone = new Bucket();
    private Bucket verifyIp = new Bucket();
    private Bucket verifyChallenge = new Bucket();

    public static class Bucket {
        private long capacity;
        private double refillPerSecond;

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        boolean isConfigured() {
            return capacity > 0 && refillPerSecond > 0;
        }
    }

    public String getHmacKeyB64() {
        return hmacKeyB64;
    }

    public void setHmacKeyB64(String hmacKeyB64) {
        this.hmacKeyB64 = hmacKeyB64;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public long getResendCooldownSeconds() {
        return resendCooldownSeconds;
    }

    public void setResendCooldownSeconds(long resendCooldownSeconds) {
        this.resendCooldownSeconds = resendCooldownSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getGrantTtlSeconds() {
        return grantTtlSeconds;
    }

    public void setGrantTtlSeconds(long grantTtlSeconds) {
        this.grantTtlSeconds = grantTtlSeconds;
    }

    public long getDeliveryTimeoutSeconds() {
        return deliveryTimeoutSeconds;
    }

    public void setDeliveryTimeoutSeconds(long deliveryTimeoutSeconds) {
        this.deliveryTimeoutSeconds = deliveryTimeoutSeconds;
    }

    public String getProviderMode() {
        return providerMode;
    }

    public void setProviderMode(String providerMode) {
        this.providerMode = providerMode;
    }

    public HttpOtpGatewayProperties getHttp() {
        return http;
    }

    public void setHttp(HttpOtpGatewayProperties http) {
        this.http = http;
    }

    public Bucket getRequestIp() {
        return requestIp;
    }

    public void setRequestIp(Bucket requestIp) {
        this.requestIp = requestIp;
    }

    public Bucket getRequestPhone() {
        return requestPhone;
    }

    public void setRequestPhone(Bucket requestPhone) {
        this.requestPhone = requestPhone;
    }

    public Bucket getVerifyIp() {
        return verifyIp;
    }

    public void setVerifyIp(Bucket verifyIp) {
        this.verifyIp = verifyIp;
    }

    public Bucket getVerifyChallenge() {
        return verifyChallenge;
    }

    public void setVerifyChallenge(Bucket verifyChallenge) {
        this.verifyChallenge = verifyChallenge;
    }

    /** Invalid policy must never start silently. */
    @PostConstruct
    void validate() {
        if (ttlSeconds <= 0) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.ttl-seconds must be > 0");
        }
        if (resendCooldownSeconds < 0) {
            throw new IllegalStateException(
                    "tazzzo.customer-auth.otp.resend-cooldown-seconds must be >= 0");
        }
        if (maxAttempts <= 0) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.max-attempts must be > 0");
        }
        if (grantTtlSeconds <= 0) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.grant-ttl-seconds must be > 0");
        }
        if (deliveryTimeoutSeconds <= 0) {
            throw new IllegalStateException("tazzzo.customer-auth.otp.delivery-timeout-seconds must be > 0");
        }
    }

    /** Whether every OTP rate-limit bucket is configured — required whenever a shared store exists. */
    boolean rateLimitBucketsConfigured() {
        return requestIp.isConfigured() && requestPhone.isConfigured()
                && verifyIp.isConfigured() && verifyChallenge.isConfigured();
    }
}
