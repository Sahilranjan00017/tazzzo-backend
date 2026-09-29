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
 * <p><b>Race safety (PR-14A hardening, M4):</b> {@code release} is CAS-guarded
 * ({@code RESERVED -> RELEASED} only) and reports whether THIS call actually caused that
 * transition ({@link InventoryReservationLifecycleResult#transitioned()}) versus merely observing
 * the reservation already terminal. This worker counts {@code released}/
 * {@code inventory_reservation_expired} ONLY when it genuinely won that transition — if a
 * concurrent explicit release or a confirming {@code consume} already resolved the reservation, this
 * worker's own call correctly reports "no-op" and is NOT counted as an expiry, so
 * {@code reconcileExpired}'s return value always means "reservations THIS call expired", never
 * "reservations that happened to already be gone by the time this call reached them".
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

    /** @return how many expired reservations THIS call actually released (never a race it lost). */
    public int reconcileExpired(int batchSize) {
        List<Document> batch = reservations.findExpiredBatch(clock.instant(), batchSize);
        int released = 0;
        for (Document doc : batch) {
            String reservationId = doc.getString("_id");
            try {
                InventoryReservationLifecycleResult result = service.releaseWithOutcome(
                        new InventoryReservationId(reservationId));
                if (result.transitioned()) {
                    released++;
                    observability.expired();
                }
                // transitioned() == false: already RELEASED by something else in the meantime —
                // an ordinary race this worker lost, not an error, not counted as an expiry.
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
