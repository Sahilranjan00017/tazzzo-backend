package com.tazzzo.customer.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-14B — bounded observability. Tags are ONLY closed enums; never {@code orderId},
 * {@code customerId}, {@code quoteId}, {@code reservationId}, or {@code skuId}. A registry fault is
 * swallowed — instrumentation never changes a business outcome.
 *
 * <p><b>{@code order_create_success} semantics (deliberately defined explicitly, per this PR's
 * review):</b> recorded for a "successful create-OR-replay" operation — i.e. once for the
 * request that actually inserts a new Order, AND once for a later request that hits the durable
 * fast-path/in-transaction replay and returns the SAME existing Order unchanged. Both are, from the
 * caller's point of view, a successful {@code createOrder} call; distinguishing "genuinely new" from
 * "idempotent replay" is not a currently-needed metric dimension and would require a
 * high-cardinality-free extra tag this PR does not introduce speculatively. Recorded ONLY after
 * {@code OrderService}'s outer transaction (or the pre-transaction fast path) has actually returned
 * a value — never before, and never from inside a session-aware method that cannot know whether the
 * caller's own transaction will commit (this class has none; {@code OrderService} owns its own
 * outer transaction end-to-end, so this asymmetry — familiar from
 * {@code InventoryReservationObservability} — does not otherwise arise here).
 */
@Component
public class OrderObservability {

    private static final Logger log = LoggerFactory.getLogger(OrderObservability.class);

    private final MeterRegistry registry;

    public OrderObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Successful create-or-replay — see class javadoc for exact semantics. */
    public void success() {
        safely(() -> Counter.builder("order_create_success").register(registry).increment());
    }

    public void failure(OrderFailure.Reason reason) {
        safely(() -> Counter.builder("order_create_failure")
                .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("order metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
