package com.tazzzo.membership;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-16A-1 — bounded observability. Tags are ONLY closed enums; never {@code membershipId},
 * {@code customerId}, {@code grantRef} or {@code planId}. A registry fault is swallowed —
 * instrumentation never changes a business outcome.
 *
 * <p>Recorded by the standalone {@code MembershipService} methods only, and only AFTER their own
 * {@code Tx.call} (and any recovery) has returned: a retried transaction callback never records
 * anything, and a session-aware Membership seam (a later PR) will emit nothing at all.
 */
@Component
public class MembershipObservability {

    private static final Logger log = LoggerFactory.getLogger(MembershipObservability.class);

    public enum Operation { GRANT }

    private final MeterRegistry registry;

    public MembershipObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void success(Operation operation) {
        safely(() -> Counter.builder("membership_operation_success")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    public void failure(Operation operation, MembershipFailure.Reason reason) {
        safely(() -> Counter.builder("membership_failure")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    public void transition(MembershipStatus from, MembershipStatus to) {
        safely(() -> Counter.builder("membership_transition")
                .tag("from", from.name().toLowerCase(Locale.ROOT)).tag("to", to.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("membership metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
