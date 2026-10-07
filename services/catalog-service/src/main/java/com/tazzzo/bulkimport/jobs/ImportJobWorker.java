package com.tazzzo.bulkimport.jobs;

import com.tazzzo.bulkimport.BulkImportDtos;
import com.tazzzo.bulkimport.ProductImportValidator;
import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorType;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The background side of an import job. One tick claims one job in a working state (VALIDATING or APPLYING) under a
 * lease, processes rows from the job's cursor in bounded batches until the rows run out or the tick's time budget is
 * spent, then releases the lease (or finishes the phase). Progress is persisted per batch — cursor, counters and every
 * row's outcome — so a restart resumes where it stopped and never re-applies a row.
 *
 * <p>Validation never writes to the catalogue. Apply re-validates each batch (the same checks as {@code POST
 * /api/v1/admin/imports/products}: a product that appeared meanwhile is UNCHANGED or a domain failure, never a duplicate)
 * and mints every valid draft through {@link MintService}, attributed to the approving admin. A domain failure (identity
 * collision, governance change, schema violation) fails that row alone; anything else is the datastore failing, which
 * PAUSES the job at the current cursor for an admin to resume.
 */
public class ImportJobWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportJobWorker.class);

    /** What a tick did; {@code paused} when the datastore failed mid-apply. */
    public record Tick(String jobId, int rows, ImportJob.Status endedAs, boolean paused) {
        public static final Tick IDLE = new Tick(null, 0, null, false);
    }

    private final ImportJobRepository repo;
    private final ImportJobService service;
    private final ProductImportValidator validator;
    private final MintService mint;
    private final Clock clock;
    private final int batchSize;
    private final long leaseMs;
    private final long tickBudgetMs;

    public ImportJobWorker(ImportJobRepository repo, ImportJobService service, ProductImportValidator validator, MintService mint,
                           Clock clock, int batchSize, long leaseMs, long tickBudgetMs) {
        this.repo = Objects.requireNonNull(repo);
        this.service = Objects.requireNonNull(service);
        this.validator = Objects.requireNonNull(validator);
        this.mint = Objects.requireNonNull(mint);
        this.clock = Objects.requireNonNull(clock);
        if (batchSize < 1 || batchSize > 5000) throw new IllegalArgumentException("import batch size must be 1..5000");
        if (leaseMs < 1000 || tickBudgetMs < 1 || tickBudgetMs >= leaseMs) {
            throw new IllegalArgumentException("import lease must be >= 1 s and longer than the tick budget");
        }
        this.batchSize = batchSize;
        this.leaseMs = leaseMs;
        this.tickBudgetMs = tickBudgetMs;
    }

    /** One tick: at most one job, at most the time budget. Never throws. */
    public Tick tick() {
        String lease = ImportJobRepository.newLeaseToken();
        ImportJob job;
        try {
            job = repo.claim(lease, leaseMs);
        } catch (RuntimeException e) {
            log.warn("import_job_claim_failed error={}", e.getClass().getSimpleName());
            return Tick.IDLE;
        }
        if (job == null) return Tick.IDLE;
        try {
            return job.status() == ImportJob.Status.VALIDATING ? validate(job, lease) : apply(job, lease);
        } catch (RuntimeException e) {
            // the lease expires on its own; the next claim resumes from the last persisted cursor
            log.warn("import_job_tick_failed job={} status={} error={}", job.id(), job.status(), e.getClass().getSimpleName());
            return new Tick(job.id(), 0, job.status(), false);
        }
    }

    // ---------------------------------------------------------------- validation phase

    private Tick validate(ImportJob job, String lease) {
        long deadline = clock.millis() + tickBudgetMs;
        long cursor = job.nextRow();
        int done = 0;
        while (cursor < job.rowsTotal()) {
            List<Document> page = repo.page(job.id(), cursor, batchSize);
            if (page.isEmpty()) break;
            Batch b = Batch.of(page, service);
            ImportJob.Counts delta = ImportJob.Counts.ZERO;
            ProductImportValidator.RowChecks checks = validator.validateRows(b.requests);
            long valid = 0, unchanged = 0, invalid = 0;
            for (BulkImportDtos.RowError e : checks.errors()) {
                Document r = b.rows.get(e.row());
                repo.setValidation(job.id(), r.getLong("row"), "INVALID", e.code(), e.message());
                invalid++;
            }
            for (Map.Entry<Integer, ProductDraft> e : checks.drafts().entrySet()) {
                Document r = b.rows.get(e.getKey());
                boolean same = checks.unchanged().contains(e.getValue().id());
                repo.setValidation(job.id(), r.getLong("row"), same ? "UNCHANGED" : "VALID", null, null);
                if (same) unchanged++; else valid++;
            }
            delta = new ImportJob.Counts(valid, unchanged, invalid, b.duplicates, 0, 0, 0);
            cursor = page.get(page.size() - 1).getLong("row") + 1;
            done += page.size();
            if (!repo.progress(job.id(), lease, cursor, delta, leaseMs)) {
                log.info("import_job_lease_lost job={} phase=validate", job.id());
                return new Tick(job.id(), done, job.status(), false);
            }
            if (clock.millis() >= deadline) break;
        }
        if (cursor >= job.rowsTotal()) {
            ImportJob after = repo.find(job.id());
            boolean clean = after != null && after.counts().invalid() == 0 && after.counts().duplicate() == 0;
            ImportJob.Status end = clean ? ImportJob.Status.VALIDATED : ImportJob.Status.REJECTED;
            repo.finishPhase(job.id(), lease, end, null);
            log.info("import_job_validated job={} rows={} result={}", job.id(), job.rowsTotal(), end);
            return new Tick(job.id(), done, end, false);
        }
        repo.releaseLease(job.id(), lease);
        return new Tick(job.id(), done, ImportJob.Status.VALIDATING, false);
    }

    // ---------------------------------------------------------------- apply phase

    private Tick apply(ImportJob job, String lease) {
        ImportJob.Actor by = job.approvedBy();
        if (by == null) {
            repo.finishPhase(job.id(), lease, ImportJob.Status.PAUSED, "the job has no approver");
            return new Tick(job.id(), 0, ImportJob.Status.PAUSED, true);
        }
        Actor actor = new Actor(ActorType.valueOf(by.type()), by.id(), by.credentialId(), by.requestId());
        long deadline = clock.millis() + tickBudgetMs;
        long cursor = job.nextRow();
        int done = 0;
        while (cursor < job.rowsTotal()) {
            List<Document> page = repo.page(job.id(), cursor, batchSize);
            if (page.isEmpty()) break;
            Batch b = Batch.of(page, service);
            long applied = 0, unchanged = 0, failed = 0;
            String pauseReason = null;
            // only rows the validation pass accepted are candidates; everything else keeps its validation verdict
            List<Integer> candidates = new ArrayList<>();
            List<CreateProductRequest> requests = new ArrayList<>();
            for (int i = 0; i < b.rows.size(); i++) {
                Document v = b.rows.get(i).get("validation", Document.class);
                String outcome = v == null ? null : v.getString("outcome");
                if ("VALID".equals(outcome) || "UNCHANGED".equals(outcome)) {
                    candidates.add(i);
                    requests.add(b.requests.get(i));
                }
            }
            ProductImportValidator.RowChecks checks = requests.isEmpty() ? null : validator.validateRows(requests);
            Map<Integer, BulkImportDtos.RowError> nowInvalid = new TreeMap<>();
            if (checks != null) for (BulkImportDtos.RowError e : checks.errors()) nowInvalid.put(e.row(), e);
            long resumeAt = -1;
            for (int c = 0; c < candidates.size(); c++) {
                Document r = b.rows.get(candidates.get(c));
                long rowNo = r.getLong("row");
                BulkImportDtos.RowError inv = nowInvalid.get(c);
                if (inv != null) {
                    repo.setApply(job.id(), rowNo, "FAILED", null, inv.code(), inv.message());
                    failed++;
                    continue;
                }
                ProductDraft draft = checks.drafts().get(c);
                if (checks.unchanged().contains(draft.id())) {
                    repo.setApply(job.id(), rowNo, "UNCHANGED", null, null, null);
                    unchanged++;
                    continue;
                }
                try {
                    mint.mint(actor, draft);
                    repo.setApply(job.id(), rowNo, "APPLIED", null, null, null);
                    applied++;
                } catch (RuntimeException e) {
                    if (com.tazzzo.bulkimport.BulkImportService.catalogueDomainFailure(e)) {
                        repo.setApply(job.id(), rowNo, "FAILED", null, failureCode(e), e.getMessage());
                        failed++;
                    } else {
                        // the datastore failed: stop HERE. The cursor is set to this row so a resume retries it first; the
                        // rows after it keep no apply verdict and are reached by the resumed cursor
                        pauseReason = e.getClass().getSimpleName();
                        resumeAt = rowNo;
                        log.warn("import_job_paused job={} row={} error={}", job.id(), rowNo, pauseReason);
                        repo.setApply(job.id(), rowNo, "NOT_ATTEMPTED", null, "UNAVAILABLE", "the datastore failed; the job paused here");
                        break;
                    }
                }
            }
            // counts.unchanged was settled by the validation pass; the apply pass only adds what it did
            ImportJob.Counts delta = new ImportJob.Counts(0, 0, 0, 0, applied, failed, 0);
            if (pauseReason != null) {
                repo.progress(job.id(), lease, resumeAt, delta, leaseMs);
                repo.finishPhase(job.id(), lease, ImportJob.Status.PAUSED, pauseReason);
                return new Tick(job.id(), done + page.size(), ImportJob.Status.PAUSED, true);
            }
            cursor = page.get(page.size() - 1).getLong("row") + 1;
            done += page.size();
            if (!repo.progress(job.id(), lease, cursor, delta, leaseMs)) {
                log.info("import_job_lease_lost job={} phase=apply", job.id());
                return new Tick(job.id(), done, job.status(), false);
            }
            if (clock.millis() >= deadline) break;
        }
        if (cursor >= job.rowsTotal()) {
            repo.finishPhase(job.id(), lease, ImportJob.Status.COMPLETED, null);
            ImportJob after = repo.find(job.id());
            log.info("import_job_completed job={} rows={} applied={} failed={}", job.id(), job.rowsTotal(),
                    after == null ? -1 : after.counts().applied(), after == null ? -1 : after.counts().failed());
            return new Tick(job.id(), done, ImportJob.Status.COMPLETED, false);
        }
        repo.releaseLease(job.id(), lease);
        return new Tick(job.id(), done, ImportJob.Status.APPLYING, false);
    }

    static String failureCode(RuntimeException e) {
        if (e instanceof com.tazzzo.catalog.tx.IdentityCollisionException) return "IDENTITY_COLLISION";
        if (e instanceof com.tazzzo.catalog.tx.AttributeViolationException) return "ATTRIBUTE_VIOLATION";
        if (e instanceof com.tazzzo.catalog.tx.EvidenceGateException) return "EVIDENCE_REQUIRED";
        return "CATALOGUE_REJECTED";
    }

    /** One page of rows with their decoded requests; a row that is already DUPLICATE is kept in place but not validated. */
    private record Batch(List<Document> rows, List<CreateProductRequest> requests, long duplicates) {
        static Batch of(List<Document> page, ImportJobService service) {
            List<Document> rows = new ArrayList<>();
            List<CreateProductRequest> requests = new ArrayList<>();
            long dup = 0;
            for (Document r : page) {
                Document v = r.get("validation", Document.class);
                if (v != null && "DUPLICATE".equals(v.getString("outcome"))) {
                    dup++;
                    continue;
                }
                rows.add(r);
                requests.add(service.fromPayload(r.get("payload", Document.class)));
            }
            return new Batch(rows, requests, dup);
        }
    }
}
