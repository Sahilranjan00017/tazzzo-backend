package com.tazzzo.catalog.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Bounded observability for INTERNAL admin-surface authentication/authorization rejections ({@code admin_auth_rejected}).
 *
 * <p><b>Cardinality is a contract.</b> The only tag is {@code reason}, a closed {@link Reason} value: never a token, actor
 * id, request id or path. A registry fault never changes the request outcome: every recording is wrapped and swallowed
 * (the {@code CustomerAuthObservability} posture).
 */
@Component
public class AdminAuthObservability {

    /**
     * {@code UNAUTHENTICATED}: missing credential, or one no configured authenticator recognises (401).
     * {@code FORBIDDEN}: an authenticated role may not write (403). Human OIDC: {@code INVALID_TOKEN}, {@code EXPIRED_TOKEN},
     * {@code DOMAIN_MISMATCH}, {@code EMAIL_UNVERIFIED} (401); {@code NOT_ALLOWLISTED}, {@code DISABLED} (403).
     */
    public enum Reason {
        UNAUTHENTICATED, FORBIDDEN,
        INVALID_TOKEN, EXPIRED_TOKEN, DOMAIN_MISMATCH, EMAIL_UNVERIFIED, NOT_ALLOWLISTED, DISABLED
    }

    private static final Logger log = LoggerFactory.getLogger(AdminAuthObservability.class);

    private final MeterRegistry registry;

    public AdminAuthObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void rejected(Reason reason) {
        try {
            Counter.builder("admin_auth_rejected").tag("reason", reason.name().toLowerCase(Locale.ROOT))
                    .register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("admin auth metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
