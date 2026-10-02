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

    /** {@code UNAUTHENTICATED}: missing or unknown credential (401). {@code FORBIDDEN}: a role may not write (403). */
    public enum Reason { UNAUTHENTICATED, FORBIDDEN }

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
