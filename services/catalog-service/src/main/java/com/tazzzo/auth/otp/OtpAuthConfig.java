package com.tazzzo.auth.otp;

import com.tazzzo.catalog.ratelimit.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR-11B — wires the OTP module's optional beans, fail-closed exactly like
 * {@code ConsumerRateLimitConfig}/{@code ConsumerAdmissionGate}: a missing dependency is a NULL bean,
 * never a permissive default, and the service layer ({@code OtpService}) treats null as UNAVAILABLE
 * (503) rather than "unlimited" or "delivered".
 */
@Configuration
@EnableConfigurationProperties(OtpAuthProperties.class)
public class OtpAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(OtpAuthConfig.class);

    /**
     * Reuses the SAME {@link RateLimitStore} the consumer surface uses — no independent Redis
     * wiring for OTP. If the store is absent (consumer-rate-limit DISABLED), OR the OTP buckets
     * are not configured, OTP rate limiting is simply unavailable and the endpoints fail closed
     * with 503 at request time (see {@code OtpService}) — NOT a startup failure. A hard startup
     * throw here would be wrong: a deployment (or, notably, one of this repository's MANY existing
     * test suites) may legitimately run a {@code RateLimitStore} for the consumer surface without
     * ever intending to serve the OTP surface at all, and must not be forced to configure OTP
     * buckets it will never use.
     */
    @Bean
    public OtpRateLimiter otpRateLimiter(ObjectProvider<RateLimitStore> storeProvider,
                                         OtpAuthProperties properties) {
        RateLimitStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return null;
        }
        if (!properties.rateLimitBucketsConfigured()) {
            log.warn("otp_rate_limiter_unavailable reason=buckets_not_configured");
            return null;
        }
        return new OtpRateLimiter(store, properties);
    }

    /**
     * NO default production provider. {@code provider-mode} unset/blank -> no bean -> the OTP
     * request endpoint fails closed with 503 rather than silently discarding an OTP.
     */
    @Bean
    public OtpDeliveryProvider otpDeliveryProvider(OtpAuthProperties properties,
                                                   ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        String mode = properties.getProviderMode();
        if (mode == null || mode.isBlank()) {
            return null;
        }
        if ("LOGGING".equals(mode)) {
            return new LoggingOtpDeliveryProvider();
        }
        if ("HTTP".equals(mode)) {
            // fail closed at startup on an unsafe/incomplete gateway configuration (never at the first customer request)
            return new HttpOtpDeliveryProvider(properties.getHttp(), properties.getDeliveryTimeoutSeconds(),
                    registry.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new));
        }
        throw new IllegalStateException(
                "tazzzo.customer-auth.otp.provider-mode must be exactly LOGGING, HTTP or unset, was: '"
                        + mode + "'");
    }
}
