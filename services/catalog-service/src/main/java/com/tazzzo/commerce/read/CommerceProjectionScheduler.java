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
 * <p><b>Activation requires BOTH flags (PR-10A review #12 / final gate):</b> the master
 * {@code tazzzo.scheduler.enabled} stays the global operational kill switch for ALL scheduled
 * jobs, and the dedicated subordinate {@code tazzzo.scheduler.card-projection-enabled} turns on
 * THIS loop specifically — so a dedicated worker instance can run projection rebuilds without
 * starting the unrelated Catalog merge/taint/rollup workers, yet {@code tazzzo.scheduler.enabled=false}
 * still stops everything. {@code @ConditionalOnProperty} with multiple {@code name}s requires ALL
 * of them to be {@code true}, giving exactly: (master=true AND card=true) ⇒ ON, otherwise OFF.
 * Off in every test profile. {@code tazzzo.freshness.enabled} (the source-hook/queue switch) is
 * deliberately INDEPENDENT — an app instance may produce rebuild requests without being a worker.
 *
 * <p>Two ticks: a frequent DRAIN of the rebuild queue, and a slower bounded RECONCILE (rolling
 * drift re-derivation + orphan cleanup). Both are guarded so a tick failure leaves work pending for
 * retry and never crashes the scheduler.
 */
@Component
@ConditionalOnProperty(
        name = {"tazzzo.scheduler.enabled", "tazzzo.scheduler.card-projection-enabled"},
        havingValue = "true")
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
