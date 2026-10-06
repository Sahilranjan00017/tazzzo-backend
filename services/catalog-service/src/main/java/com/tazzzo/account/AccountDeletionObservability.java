package com.tazzzo.account;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Bounded counters only: outcomes and failure reasons, never identifiers. */
@Component
public class AccountDeletionObservability {

    private final MeterRegistry registry;

    public AccountDeletionObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void deleted() {
        safely(() -> Counter.builder("customer_account_deletion_success").tag("outcome", "deleted").register(registry).increment());
    }

    public void alreadyDeleted() {
        safely(() -> Counter.builder("customer_account_deletion_success").tag("outcome", "already_deleted").register(registry).increment());
    }

    public void failure(AccountDeletionFailure.Reason reason) {
        safely(() -> Counter.builder("customer_account_deletion_failure").tag("reason", reason.name()).register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException ignored) {
            // metrics never break the request
        }
    }
}
