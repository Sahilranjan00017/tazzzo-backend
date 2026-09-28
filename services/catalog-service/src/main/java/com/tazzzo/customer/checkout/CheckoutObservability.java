package com.tazzzo.customer.checkout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-13A — bounded observability. Tags are ONLY closed enums; never customerId, addressId, skuId,
 * quoteId, PIN, Idempotency-Key or requestId. Failures are recorded at ONE boundary
 * ({@link CheckoutExceptionHandler}); successes only AFTER the durable read/commit. A registry fault
 * is swallowed — instrumentation never changes a business outcome.
 */
@Component
public class CheckoutObservability {

    private static final Logger log = LoggerFactory.getLogger(CheckoutObservability.class);

    public enum Operation { CREATE_QUOTE, READ_QUOTE }

    private final MeterRegistry registry;

    public CheckoutObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void quoteSuccess() {
        safely(() -> Counter.builder("customer_checkout_quote_success").register(registry).increment());
    }

    public void quoteReadSuccess() {
        safely(() -> Counter.builder("customer_checkout_quote_read_success").register(registry).increment());
    }

    public void failure(Operation operation, CheckoutFailure.Reason reason) {
        safely(() -> Counter.builder("customer_checkout_failure")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    /** An UNEXPECTED server defect (catch-all boundary only); deliberately not a domain Reason. */
    public void internalFailure(Operation operation) {
        safely(() -> Counter.builder("customer_checkout_failure")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("reason", "internal").register(registry).increment());
    }

    public void itemRejection(CheckoutItemReason reason) {
        safely(() -> Counter.builder("customer_checkout_item_rejection")
                .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("customer checkout metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
