package com.tazzzo.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PR-14A — the scheduled driver of {@link InventoryReservationExpiryWorker}. Requires BOTH the
 * master {@code tazzzo.scheduler.enabled} kill switch AND the dedicated
 * {@code tazzzo.scheduler.inventory-reservation-expiry-enabled} flag — the same two-flag discipline
 * {@code CommerceProjectionScheduler} already uses, so a dedicated worker instance can run this
 * loop alone. Off in every test profile.
 */
@Component
@ConditionalOnProperty(
        name = {"tazzzo.scheduler.enabled", "tazzzo.scheduler.inventory-reservation-expiry-enabled"},
        havingValue = "true")
public class InventoryReservationScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationScheduler.class);

    private final InventoryReservationExpiryWorker worker;
    private final InventoryReservationProperties properties;

    public InventoryReservationScheduler(InventoryReservationExpiryWorker worker,
                                         InventoryReservationProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${tazzzo.scheduler.inventory-reservation-expiry-ms:30000}")
    public void reconcileExpired() {
        try {
            worker.reconcileExpired(properties.getExpiryBatchSize());
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                return; // shutdown; leave work pending
            }
            log.error("inventory_reservation_scheduler_tick_failed type={}", e.getClass().getSimpleName());
        }
    }
}
