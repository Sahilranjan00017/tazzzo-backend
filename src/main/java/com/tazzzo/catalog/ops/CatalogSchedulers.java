package com.tazzzo.catalog.ops;

import com.tazzzo.catalog.tx.MergeService;
import com.tazzzo.catalog.tx.RollupService;
import com.tazzzo.catalog.tx.TaintService;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * OPERATIONAL LAYER ONLY — closes finding F5 (crash-safe workers that nothing invoked).
 *
 * This class contains NO business logic and performs NO writes. Every method is a bare
 * delegation to an existing domain worker; all mutation still happens inside those services
 * through WritePath. Adding, removing or reordering anything here can change WHEN work runs,
 * never WHAT it does.
 *
 * Safety model (already provided by the workers, not by this class):
 *  - lease-claimed items (merge finalizer, taint, stamp) → concurrent runs cannot collide;
 *  - deterministic work-item ids → repeated execution is idempotent;
 *  - checkpoints → a run interrupted mid-scan resumes, it does not restart;
 *  - rolled-flag purge → a stalled rollup can never lose price history.
 * Each tick is therefore safe to run repeatedly, on any instance, at any time.
 *
 * Exceptions are caught and logged per tick: a failing worker must not kill the scheduler
 * thread and stop the others. The work stays pending and is retried on the next tick.
 */
@Component
@ConditionalOnProperty(value = "tazzzo.scheduler.enabled", havingValue = "true")
public class CatalogSchedulers {

    private static final Logger log = LoggerFactory.getLogger(CatalogSchedulers.class);

    private final MergeService mergeService;
    private final TaintService taintService;
    private final RollupService rollupService;
    private final TaxonomyChangeService taxonomyChangeService;

    @Value("${tazzzo.scheduler.taint-batch-size:100}")
    private int taintBatchSize;

    @Value("${tazzzo.scheduler.stamp-batch-size:100}")
    private int stampBatchSize;

    public CatalogSchedulers(MergeService mergeService, TaintService taintService,
                             RollupService rollupService, TaxonomyChangeService taxonomyChangeService) {
        this.mergeService = mergeService;
        this.taintService = taintService;
        this.rollupService = rollupService;
        this.taxonomyChangeService = taxonomyChangeService;
    }

    /** Completes merges: repoints offers/bundles, flips lifecycles, closes the outbox. */
    @Scheduled(fixedDelayString = "${tazzzo.scheduler.merge-finalizer-ms:30000}")
    public void mergeFinalizer() {
        guard("merge-finalizer", mergeService::runFinalizer);
    }

    /** Fans a retracted evidence out to per-product revalidation obligations. */
    @Scheduled(fixedDelayString = "${tazzzo.scheduler.taint-ms:30000}")
    public void taintCascade() {
        guard("taint-cascade", () -> taintService.runTaintWorker(taintBatchSize));
    }

    /** Fans a taxonomy change out to per-product reclassification obligations. */
    @Scheduled(fixedDelayString = "${tazzzo.scheduler.stamp-ms:30000}")
    public void stampFanOut() {
        guard("stamp-fanout", () -> taxonomyChangeService.runStampWorker(stampBatchSize));
    }

    /** Aggregates price events, then purges ONLY those durably marked rolled. */
    @Scheduled(fixedDelayString = "${tazzzo.scheduler.rollup-ms:3600000}")
    public void priceRollup() {
        guard("price-rollup", () -> {
            rollupService.rollup();              // M3: boundary owned by the domain
            long purged = rollupService.purge();
            if (purged > 0) log.info("price rollup purged {} rolled events", purged);
        });
    }

    private void guard(String name, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || e instanceof com.mongodb.MongoInterruptedException) {
                Thread.currentThread().interrupt();      // m5: preserve interrupt on shutdown
                log.debug("scheduled worker '{}' interrupted during shutdown", name);
                return;
            }
            // Work remains pending and is retried next tick. Logged at ERROR: a silently
            // failing worker is the failure mode this whole layer exists to prevent.
            log.error("scheduled worker '{}' failed; work stays pending for retry", name, e);
        }
    }
}
