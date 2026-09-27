package com.tazzzo.auth.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-11C hardening (Finding 2) — bounded observability for {@link CustomerSessionService}'s
 * lifecycle transitions. Every counter here is either untagged or tagged with a
 * {@link SessionAuthFailure.Reason} value — a closed, compile-time-bounded enum. Never a
 * customerId, sessionId, phone, grantId, refresh token, access token, or requestId.
 *
 * <p>Instrumentation is subordinate to the business result: a registry fault is swallowed, never
 * allowed to turn a valid session-lifecycle outcome into a failure.
 */
@Component
public class SessionObservability {

    private static final Logger log = LoggerFactory.getLogger(SessionObservability.class);

    private final MeterRegistry registry;

    public SessionObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void sessionCreateSuccess() {
        safely(() -> Counter.builder("session_create_success").register(registry).increment());
    }

    public void sessionCreateFailure(SessionAuthFailure.Reason reason) {
        safely(() -> Counter.builder("session_create_failure")
                .tag("reason", tag(reason)).register(registry).increment());
    }

    public void refreshSuccess() {
        safely(() -> Counter.builder("refresh_success").register(registry).increment());
    }

    public void refreshFailure(SessionAuthFailure.Reason reason) {
        safely(() -> Counter.builder("refresh_failure")
                .tag("reason", tag(reason)).register(registry).increment());
    }

    public void logout() {
        safely(() -> Counter.builder("logout").register(registry).increment());
    }

    private static String tag(SessionAuthFailure.Reason reason) {
        return reason.name().toLowerCase(Locale.ROOT);
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("session metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
