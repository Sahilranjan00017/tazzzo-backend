package com.tazzzo.customer.address;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-12B — bounded observability for the address domain. {@code reason}/{@code operation}/
 * {@code result} tags are always closed enums. NEVER customerId, addressId, phone, PIN, lat/lng,
 * label text, requestId, IP, serviceAreaId (unbounded cardinality, even though it is a public field
 * in the existing serviceability contract), or fulfillmentLocationId (internal).
 *
 * <p>Instrumentation is subordinate to the business result: a registry fault is swallowed, never
 * allowed to turn a valid/invalid outcome into something else. Per mission §33: success counters
 * are recorded ONLY after a {@code Tx.call} returns successfully -- never from inside a transaction
 * callback, which may execute more than once on retry.
 */
@Component
public class AddressObservability {

    private static final Logger log = LoggerFactory.getLogger(AddressObservability.class);

    public enum Operation { LIST, READ, CREATE, UPDATE, DELETE, SET_DEFAULT }

    private final MeterRegistry registry;

    public AddressObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void listSuccess() {
        safely(() -> Counter.builder("customer_address_list_success").register(registry).increment());
    }

    public void readSuccess() {
        safely(() -> Counter.builder("customer_address_read_success").register(registry).increment());
    }

    public void createSuccess() {
        safely(() -> Counter.builder("customer_address_create_success").register(registry).increment());
    }

    public void updateSuccess() {
        safely(() -> Counter.builder("customer_address_update_success").register(registry).increment());
    }

    public void deleteSuccess() {
        safely(() -> Counter.builder("customer_address_delete_success").register(registry).increment());
    }

    public void setDefaultSuccess() {
        safely(() -> Counter.builder("customer_address_default_set_success").register(registry).increment());
    }

    public void failure(Operation operation, AddressFailure.Reason reason) {
        safely(() -> Counter.builder("customer_address_failure")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    public void serviceabilityResult(AddressServiceabilityEvaluator.Result result) {
        safely(() -> Counter.builder("address_serviceability_result")
                .tag("result", result.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("customer address metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
