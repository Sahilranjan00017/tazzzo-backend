package com.tazzzo.bulkimport.jobs;

import com.tazzzo.bulkimport.BulkImportDtos;
import com.tazzzo.bulkimport.ProductImportValidator;
import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.Tx;
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
 * spent, then releases the lease (or finishes the phase). Progress is persisted so a restart resumes where it stopped
 * and never re-applies a row.
 *
 * <p>Validation never writes to the catalogue; its verdicts are written per batch, after the lease is renewed for the
 * batch, so a worker that lost its lease writes nothing over the new holder's verdicts.
 *
 * <p>Apply re-validates each batch (the same checks as {@code POST /api/v1/admin/imports/products}: a product that
 * appeared meanwhile is UNCHANGED or a domain failure, never a duplicate) and mints every valid draft through
 * {@link MintService}, attributed to the approving admin. The lease is renewed BEFORE every mint and each row's verdict is
 * recorded together with the cursor and counters in one lease-guarded transaction AFTER it, so: a cancel or a lost lease
 * stops the worker at the next row (at most one row is minted past it); a row minted by a worker that then lost its
 * lease is recorded by nobody and is found UNCHANGED by the next holder; counts and verdicts can never disagree. A
 * domain failure (identity collision, governance change, schema violation) fails that row alone; anything else is the
 * datastore failing, which PAUSES the job at the current cursor for an admin to resume.
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
    private final Tx tx;
    private final Clock clock;
    private final int batchSize;
    private final long leaseMs;
    private final long tickBudgetMs;

    public ImportJobWorker(ImportJobRepository repo, ImportJobService service, ProductImportValidator validator, MintService mint,
                           Tx tx, Clock clock, int batchSize, long leaseMs, long tickBudgetMs) {
        this.repo = Objects.requireNonNull(repo);
        this.service = Objects.requireNonNull(service);
        this.validator = Objects.requireNonNull(validator);
        this.mint = Objects.requireNonNull(mint);
        this.tx = Objects.requireNonNull(tx);
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
            List<Document> page = repo.page(job.id(), cursor, batchSize, job.rowsTotal());
            if (page.isEmpty()) {
                // the ledger is shorter than rows_total (an append failed after counting): the job can never finish — say so
                repo.finishPhase(job.id(), lease, ImportJob.Status.REJECTED, "row ledger short: expected " + job.rowsTotal() + " rows, found " + cursor);
                log.warn("import_job_ledger_short job={} rows_total={} found={}", job.id(), job.rowsTotal(), cursor);
                return new Tick(job.id(), done, ImportJob.Status.REJECTED, false);
            }
            if (!repo.renewLease(job.id(), lease, leaseMs)) {
                log.info("import_job_lease_lost job={} phase=validate", job.id());
                return new Tick(job.id(), done, job.status(), false);
            }
            Batch b = Batch.of(page, service);
            ProductImportValidator.RowChecks checks = validator.validateRows(b.requests);
            long valid = 0, unchanged = 0, invalid = 0;
            List<ImportJobRepository.Verdict> verdicts = new ArrayList<>(b.rows.size());
            for (BulkImportDtos.RowError e : checks.errors()) {
                Document r = b.rows.get(e.row());
                verdicts.add(new ImportJobRepository.Verdict(r.getLong("row"), "INVALID", e.code(), e.message()));
                invalid++;
            }
            for (Map.Entry<Integer, ProductDraft> e : checks.drafts().entrySet()) {
                Document r = b.rows.get(e.getKey());
                boolean same = checks.unchanged().contains(e.getValue().id());
                verdicts.add(new ImportJobRepository.Verdict(r.getLong("row"), same ? "UNCHANGED" : "VALID", null, null));
                if (same) unchanged++; else valid++;
            }
            ImportJob.Counts delta = new ImportJob.Counts(valid, unchanged, invalid, b.duplicates, 0, 0, 0);
            cursor = page.get(page.size() - 1).getLong("row") + 1;
            done += page.size();
            // the batch's verdicts, the cursor and the counters land together, or not at all (lease lost)
            if (!repo.recordValidation(job.id(), lease, verdicts, cursor, delta, leaseMs, tx)) {
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
            List<Document> page = repo.page(job.id(), cursor, batchSize, job.rowsTotal());
            if (page.isEmpty()) {
                repo.finishPhase(job.id(), lease, ImportJob.Status.PAUSED, "row ledger short: expected " + job.rowsTotal() + " rows, found " + cursor);
                log.warn("import_job_ledger_short job={} rows_total={} found={}", job.id(), job.rowsTotal(), cursor);
                return new Tick(job.id(), done, ImportJob.Status.PAUSED, true);
            }
            Batch b = Batch.of(page, service);
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
            if (!repo.renewLease(job.id(), lease, leaseMs)) {
                log.info("import_job_lease_lost job={} phase=apply", job.id());
                return new Tick(job.id(), done, job.status(), false);
            }
            ProductImportValidator.RowChecks checks = requests.isEmpty() ? null : validator.validateRows(requests);
            Map<Integer, BulkImportDtos.RowError> nowInvalid = new TreeMap<>();
            if (checks != null) for (BulkImportDtos.RowError e : checks.errors()) nowInvalid.put(e.row(), e);
            long lastRow = cursor - 1;
            for (int c = 0; c < candidates.size(); c++) {
                Document r = b.rows.get(candidates.get(c));
                long rowNo = r.getLong("row");
                boolean validatedUnchanged = "UNCHANGED".equals(r.get("validation", Document.class).getString("outcome"));
                String outcome;
                String code = null, message = null;
                BulkImportDtos.RowError inv = nowInvalid.get(c);
                ProductDraft draft = inv == null ? checks.drafts().get(c) : null;
                if (inv != null) {
                    outcome = "FAILED"; code = inv.code(); message = inv.message();
                } else if (checks.unchanged().contains(draft.id())) {
                    outcome = "UNCHANGED";
                } else {
                    // the lease is renewed before every mint: a cancel or a lost lease stops the worker at the next row
                    if (!repo.renewLease(job.id(), lease, leaseMs)) {
                        log.info("import_job_lease_lost job={} phase=apply row={}", job.id(), rowNo);
                        return new Tick(job.id(), done, job.status(), false);
                    }
                    try {
                        mint.mint(actor, draft);
                        outcome = "APPLIED";
                    } catch (RuntimeException e) {
                        if (identityClash(e) && nowUnchanged(requests.get(c), draft)) {
                            // the product appeared between the re-validation and the mint (e.g. an earlier holder of this
                            // job's lease minted it and could not record it): it is this row's product, not a collision
                            outcome = "UNCHANGED";
                        } else if (com.tazzzo.bulkimport.BulkImportService.catalogueDomainFailure(e)) {
                            outcome = "FAILED"; code = failureCode(e); message = e.getMessage();
                        } else {
                            // the datastore failed: stop HERE with the cursor on this row, so a resume retries it first
                            String reason = e.getClass().getSimpleName();
                            log.warn("import_job_paused job={} row={} error={}", job.id(), rowNo, reason);
                            if (!repo.recordPause(job.id(), lease, rowNo, reason, tx)) {
                                log.info("import_job_lease_lost job={} phase=apply row={} verdict_dropped=NOT_ATTEMPTED", job.id(), rowNo);
                                return new Tick(job.id(), done, job.status(), false);
                            }
                            return new Tick(job.id(), done + c, ImportJob.Status.PAUSED, true);
                        }
                    }
                }
                ImportJob.Counts delta = applyDelta(validatedUnchanged, outcome);
                // the verdict, the cursor and the counters land together, or not at all (lease lost)
                if (!repo.recordApply(job.id(), lease, rowNo, outcome, code, message, rowNo + 1, delta, leaseMs, tx)) {
                    log.info("import_job_lease_lost job={} phase=apply row={} verdict_dropped={}", job.id(), rowNo, outcome);
                    return new Tick(job.id(), done, job.status(), false);
                }
                lastRow = rowNo;
            }
            cursor = page.get(page.size() - 1).getLong("row") + 1;
            done += page.size();
            // rows after the last candidate (DUPLICATE/INVALID rows at the page's end) are passed by the cursor
            if (cursor > lastRow + 1 && !repo.progress(job.id(), lease, cursor, ImportJob.Counts.ZERO, leaseMs)) {
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

    /**
     * The counter change for one applied row. {@code valid} and {@code unchanged} keep partitioning the candidates by what
     * the apply found — a VALID row whose product already exists moves to {@code unchanged}, an UNCHANGED row the apply
     * minted or failed moves to {@code valid} — so a COMPLETED job always has {@code applied + failed == valid}, and every
     * row is counted in exactly one of valid / unchanged / invalid / duplicate.
     */
    static ImportJob.Counts applyDelta(boolean validatedUnchanged, String outcome) {
        long move = 0;   // +1: unchanged -> valid; -1: valid -> unchanged
        if ("UNCHANGED".equals(outcome) && !validatedUnchanged) move = -1;
        if (!"UNCHANGED".equals(outcome) && validatedUnchanged) move = 1;
        return new ImportJob.Counts(move, -move, 0, 0, "APPLIED".equals(outcome) ? 1 : 0, "FAILED".equals(outcome) ? 1 : 0, 0);
    }

    static boolean identityClash(RuntimeException e) {
        return e instanceof com.tazzzo.catalog.tx.IdentityCollisionException
                || (e instanceof com.mongodb.MongoWriteException w && w.getError().getCode() == 11000);
    }

    /** True when the row's product now exists exactly as the row describes it (the same check as validation's UNCHANGED). */
    private boolean nowUnchanged(CreateProductRequest request, ProductDraft draft) {
        try {
            return validator.validateRows(List.of(request)).unchanged().contains(draft.id());
        } catch (RuntimeException e) {
            return false;
        }
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
