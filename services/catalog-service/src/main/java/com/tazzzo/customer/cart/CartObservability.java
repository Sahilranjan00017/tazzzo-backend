package com.tazzzo.customer.cart;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-12C — bounded observability. Tags are ONLY closed enums ({@link Operation},
 * {@link CartFailure.Reason}, {@link CartIssue}); never customerId, skuId, addressId, PIN, sessionId
 * or requestId. Failures are recorded at ONE boundary ({@link CartExceptionHandler}); the service
 * records only successes/expiry, and only AFTER its transaction returned. A registry fault is
 * swallowed — instrumentation never changes a business outcome.
 */
@Component
public class CartObservability {

    private static final Logger log = LoggerFactory.getLogger(CartObservability.class);

    public enum Operation { READ, SET_ITEM, REMOVE_ITEM, CLEAR }

    private final MeterRegistry registry;

    public CartObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void readSuccess() {
        safely(() -> Counter.builder("customer_cart_read_success").register(registry).increment());
    }

    public void mutationSuccess(Operation operation) {
        safely(() -> Counter.builder("customer_cart_mutation_success")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    public void failure(Operation operation, CartFailure.Reason reason) {
        safely(() -> Counter.builder("customer_cart_failure")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    public void itemIssue(CartIssue issue) {
        safely(() -> Counter.builder("customer_cart_item_issue")
                .tag("issue", issue.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    public void cartExpired() {
        safely(() -> Counter.builder("cart_expired").register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("customer cart metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
