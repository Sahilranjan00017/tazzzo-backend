package com.tazzzo.customer.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-15A-2 — HTTP-boundary metrics for the Order surface, with NO overlap with the domain:
 * {@code OrderService.placeCodOrder} already owns {@code order_place_cod_success}/
 * {@code order_place_cod_failure{reason}}, so this class records ONLY what the domain never sees:
 * <ul>
 *   <li>POST: requests rejected before the domain (malformed body, unsupported payment method, media
 *       type) and unexpected 500s — never an {@code OrderFailure} (the domain counted it);</li>
 *   <li>GET: the success and EVERY failure (the read records no domain metric).</li>
 * </ul>
 * Tags are closed enums only; never orderId, customerId, quoteId or requestId. A registry fault is
 * swallowed — instrumentation never changes a business outcome.
 */
@Component
public class OrderHttpObservability {

    private static final Logger log = LoggerFactory.getLogger(OrderHttpObservability.class);

    public enum Operation { PLACE, READ }

    public enum Reason { INVALID_REQUEST, PAYMENT_METHOD_UNSUPPORTED, UNSUPPORTED_MEDIA_TYPE, NOT_FOUND,
        UNAVAILABLE, INTERNAL }

    private final MeterRegistry registry;

    public OrderHttpObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void readSuccess() {
        safely(() -> Counter.builder("customer_order_read_success").register(registry).increment());
    }

    public void failure(Operation operation, Reason reason) {
        safely(() -> Counter.builder("customer_order_http_failure")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("customer order metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
