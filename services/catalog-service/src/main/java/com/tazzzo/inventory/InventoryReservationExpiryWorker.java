package com.tazzzo.inventory;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * PR-14A — reconciliation: finds a bounded batch of {@code RESERVED} headers past their
 * {@code expiresAt} and releases them through the SAME idempotent {@link InventoryReservationService#release}
 * lifecycle every other release path uses — never a special bulk update that bypasses reservation
 * rules. One poison reservation never aborts the batch: a failure is logged (safe class only) and
 * the worker moves on, exactly like {@code ProjectionRebuildWorker}'s per-item failure discipline.
 *
 * <p><b>Race safety:</b> {@code release} is itself idempotent and CAS-guarded
 * ({@code RESERVED -> RELEASED} only). If a reservation this worker is about to release was, in the
 * same window, explicitly released or consumed by something else, this worker's own
 * {@code release} call simply resolves to that already-terminal state (a no-op for
 * already-{@code RELEASED}, or an {@code INVALID_TRANSITION} it swallows for already-
 * {@code CONSUMED}) — no stock counter is ever touched twice.
 */
@Component
public class InventoryReservationExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationExpiryWorker.class);

    private final InventoryReservationRepository reservations;
    private final InventoryReservationService service;
    private final InventoryReservationObservability observability;
    private final Clock clock;

    public InventoryReservationExpiryWorker(InventoryReservationRepository reservations,
                                            InventoryReservationService service,
                                            InventoryReservationObservability observability, Clock clock) {
        this.reservations = reservations;
        this.service = service;
        this.observability = observability;
        this.clock = clock;
    }

    /** @return how many expired reservations this call actually released. */
    public int reconcileExpired(int batchSize) {
        List<Document> batch = reservations.findExpiredBatch(clock.instant(), batchSize);
        int released = 0;
        for (Document doc : batch) {
            String reservationId = doc.getString("_id");
            try {
                service.release(new InventoryReservationId(reservationId));
                released++;
                observability.expired();
            } catch (InventoryReservationFailure e) {
                // already CONSUMED by a confirmation that won the race — expected, not an error.
                if (e.reason() != InventoryReservationFailure.Reason.INVALID_TRANSITION) {
                    log.warn("inventory_reservation_expiry_release_failed reason={}", e.reason());
                }
            } catch (RuntimeException e) {
                log.warn("inventory_reservation_expiry_release_failed type={}", e.getClass().getSimpleName());
            }
        }
        return released;
    }
}
