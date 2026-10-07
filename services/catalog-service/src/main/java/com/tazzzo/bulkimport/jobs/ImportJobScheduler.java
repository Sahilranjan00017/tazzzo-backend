package com.tazzzo.bulkimport.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link ImportJobWorker#tick()}. Gated like the projection scheduler: the master {@code tazzzo.scheduler.enabled}
 * AND the subordinate {@code tazzzo.scheduler.import-jobs-enabled} must both be true, so a dedicated import worker
 * instance can run without the other workers and {@code tazzzo.scheduler.enabled=false} still stops everything. Off in
 * every test profile; tests drive the worker directly.
 */
@Component
@ConditionalOnProperty(name = {"tazzzo.scheduler.enabled", "tazzzo.scheduler.import-jobs-enabled"}, havingValue = "true")
public class ImportJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImportJobScheduler.class);

    private final ImportJobWorker worker;

    public ImportJobScheduler(ImportJobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${tazzzo.scheduler.import-jobs-tick-ms:5000}")
    public void tick() {
        try {
            ImportJobWorker.Tick t = worker.tick();
            if (t.jobId() != null) {
                log.info("import_job_tick job={} rows={} status={}", t.jobId(), t.rows(), t.endedAs());
            }
        } catch (RuntimeException e) {
            log.warn("import_job_tick_failed error={}", e.getClass().getSimpleName());
        }
    }
}
