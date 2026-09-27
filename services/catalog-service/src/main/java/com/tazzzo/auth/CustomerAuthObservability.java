package com.tazzzo.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-11C hardening (Finding 2) — bounded observability for customer-auth rejections, recorded at
 * BOTH rejection sites that produce a {@link CustomerAuthFailure} ({@code CustomerAuthFilter} and
 * {@code SessionExceptionHandler}'s logout boundary) so the same counter reflects every rejected
 * customer-authenticated request regardless of which layer rejected it.
 *
 * <p><b>Cardinality is a contract.</b> {@code reason} is always a {@link CustomerAuthFailure.Reason}
 * value — a closed, compile-time-bounded enum — never a customerId, sessionId, phone, token, or
 * requestId. Mirrors {@code ConsumerObservability}'s posture: a registry fault must never turn a
 * valid/invalid request outcome into something else, so every recording is wrapped and swallowed.
 */
@Component
public class CustomerAuthObservability {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthObservability.class);

    private final MeterRegistry registry;

    public CustomerAuthObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void authRejected(CustomerAuthFailure.Reason reason) {
        safely(() -> Counter.builder("session_auth_rejected")
                .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("customer auth metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
