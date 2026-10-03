package com.tazzzo.admin.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Bounded observability for the admin audit-read API ({@code admin_audit_read}). The only tag is {@code outcome}, a closed
 * {@link Outcome}: never an actor id, filter value, cursor, request id or token. A registry fault never changes the
 * request outcome.
 */
@Component
public class AuditReadObservability {

    public enum Outcome { SERVED, FORBIDDEN, INVALID }

    private static final Logger log = LoggerFactory.getLogger(AuditReadObservability.class);

    private final MeterRegistry registry;

    public AuditReadObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(Outcome outcome) {
        try {
            Counter.builder("admin_audit_read").tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("audit read metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
