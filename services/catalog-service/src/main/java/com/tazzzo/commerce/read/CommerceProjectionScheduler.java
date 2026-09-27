package com.tazzzo.commerce.read;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.media.MediaService;
import com.tazzzo.pricing.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Production driver of the projection freshness loop (PR-10A). Lives in {@code commerce.read}, NOT
 * {@code catalog.ops}, because {@code catalog} must not depend on {@code commerce} (ArchUnit
 * {@code catalog_does_not_depend_on_other_modules}) — so it is a SEPARATE scheduler from
 * {@link com.tazzzo.catalog.ops.CatalogSchedulers}.
 *
 * <p><b>Dedicated activation flag (PR-10A review #12):</b> gated by
 * {@code tazzzo.scheduler.card-projection-enabled}, NOT the master {@code tazzzo.scheduler.enabled}
 * — so enabling this projection loop never unintentionally starts the unrelated Catalog
 * merge/taint/rollup workers, and vice-versa. Off in every test profile, so it adds no wiring or
 * work_queue activity to the suite; production turns it on. Derivation is delegated to
 * {@link ProductCardProjectionService#rebuildOne} via {@link ProjectionRebuildWorker}.
 *
 * <p>Two ticks: a frequent DRAIN of the rebuild queue, and a slower bounded RECONCILE (rolling
 * drift re-derivation + orphan cleanup). Both are guarded so a tick failure leaves work pending for
 * retry and never crashes the scheduler.
 */
@Component
@ConditionalOnProperty(value = "tazzzo.scheduler.card-projection-enabled", havingValue = "true")
public class CommerceProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommerceProjectionScheduler.class);

    private final ProjectionRebuildWorker worker;
    private final ProjectionReconciler reconciler;
    private final int drainBatchSize;
    private final int reconcileLimit;

    public CommerceProjectionScheduler(
            MongoClient client, MongoDatabase db,
            @Value("${tazzzo.scheduler.card-rebuild-batch-size:200}") int drainBatchSize,
            @Value("${tazzzo.scheduler.card-reconcile-limit:500}") int reconcileLimit) {
        Clock clock = Clock.systemUTC();
        Tx tx = new Tx(client);
        WritePath writePath = new WritePath(db);
        ProjectionRebuildQueue queue = new ProjectionRebuildQueue(db, clock);
        // The projection service reads sources fresh on every rebuild; the PricingService/
        // MediaService here are used only as read ports (no writes, no queue needed on them).
        ProductCardProjectionService projection = new ProductCardProjectionService(
                new CatalogCardReader(db),
                new PricingService(tx, writePath, clock),
                new MediaService(tx, writePath, clock),
                db, clock);
        this.worker = new ProjectionRebuildWorker(db, projection, clock);
        this.reconciler = new ProjectionReconciler(db, queue);
        this.drainBatchSize = drainBatchSize;
        this.reconcileLimit = reconcileLimit;
    }

    @Scheduled(fixedDelayString = "${tazzzo.scheduler.card-rebuild-ms:15000}")
    public void drainRebuilds() {
        guard("card_rebuild_drain", () -> worker.drain(drainBatchSize));
    }

    @Scheduled(fixedDelayString = "${tazzzo.scheduler.card-reconcile-ms:300000}")
    public void reconcile() {
        guard("card_reconcile_drift", () -> reconciler.reconcileDrift(reconcileLimit));
        guard("card_reconcile_orphan", () -> reconciler.reconcileOrphans(reconcileLimit));
    }

    private void guard(String name, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                return; // shutdown; leave work pending
            }
            log.error("commerce_projection_scheduler_tick_failed tick={} type={}",
                    name, e.getClass().getSimpleName());
        }
    }
}
