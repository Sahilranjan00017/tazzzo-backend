package com.tazzzo.inventory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * PR-14A — bounded observability. Tags are ONLY closed enums; never {@code reservationId},
 * {@code orderId}, {@code skuId}, or {@code fulfillmentLocationId}. A registry fault is swallowed
 * — instrumentation never changes a business outcome.
 *
 * <p><b>Durable-success discipline (deliberately asymmetric):</b> {@link #success} is for the
 * STANDALONE wrapper methods only, called AFTER their own {@code Tx.call} has committed — exactly
 * the same "success only after commit" rule every other domain in this codebase follows. The
 * SESSION-AWARE port methods ({@link InventoryReservationService#reserve(com.mongodb.client.ClientSession,
 * PreparedInventoryReservation, InventoryReservationAllocation)} and friends) never call this: a
 * session-aware call is one step inside a CALLER's outer transaction, whose eventual commit or
 * rollback this class cannot observe — claiming durable success there would be a lie if the
 * caller's transaction later rolls back. {@code customer.order} composes this port and is
 * responsible for its OWN success metric, recorded after ITS OWN outer transaction commits.
 */
@Component
public class InventoryReservationObservability {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationObservability.class);

    public enum Operation { RESERVE, RELEASE, CONSUME, READ, EXPIRE }

    private final MeterRegistry registry;

    public InventoryReservationObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Standalone-wrapper success ONLY — never call from a session-aware port method. */
    public void success(Operation operation) {
        safely(() -> Counter.builder("inventory_reservation_success")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    public void failure(Operation operation, InventoryReservationFailure.Reason reason) {
        safely(() -> Counter.builder("inventory_reservation_failure")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name().toLowerCase(Locale.ROOT)).register(registry).increment());
    }

    public void transition(InventoryReservationStatus from, InventoryReservationStatus to) {
        safely(() -> Counter.builder("inventory_reservation_transition")
                .tag("from", from.name().toLowerCase(Locale.ROOT)).tag("to", to.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    public void expired() {
        safely(() -> Counter.builder("inventory_reservation_expired").register(registry).increment());
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("inventory reservation metric recording failed and was ignored: {}",
                    e.getClass().getSimpleName());
        }
    }
}
