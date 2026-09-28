package com.tazzzo.customer.profile;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-12A — bounded observability for the profile domain. {@code reason} is always a
 * {@link CustomerProfileFailure.Reason} value — a closed, compile-time-bounded enum. NEVER
 * customerId, email, displayName, phone, sessionId, requestId, IP, or installationId.
 *
 * <p>Instrumentation is subordinate to the business result: a registry fault is swallowed, never
 * allowed to turn a valid/invalid profile outcome into something else.
 */
@Component
public class CustomerProfileObservability {

    private static final Logger log = LoggerFactory.getLogger(CustomerProfileObservability.class);

    private final MeterRegistry registry;

    public CustomerProfileObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void readSuccess() {
        safely(() -> Counter.builder("customer_profile_read_success").register(registry).increment());
    }

    public void readFailure(CustomerProfileFailure.Reason reason) {
        safely(() -> Counter.builder("customer_profile_read_failure")
                .tag("reason", tag(reason)).register(registry).increment());
    }

    public void updateSuccess() {
        safely(() -> Counter.builder("customer_profile_update_success").register(registry).increment());
    }

    public void updateFailure(CustomerProfileFailure.Reason reason) {
        safely(() -> Counter.builder("customer_profile_update_failure")
                .tag("reason", tag(reason)).register(registry).increment());
        if (reason == CustomerProfileFailure.Reason.PRECONDITION_FAILED) {
            safely(() -> Counter.builder("customer_profile_precondition_failed").register(registry).increment());
        }
    }

    private static String tag(CustomerProfileFailure.Reason reason) {
        return reason.name().toLowerCase(Locale.ROOT);
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("customer profile metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
