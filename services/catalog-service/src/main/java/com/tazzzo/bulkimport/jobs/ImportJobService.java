package com.tazzzo.bulkimport.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.catalog.api.ApiDtos.CreateProductRequest;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The admin-facing side of an import job: create, append rows (streamed CSV or JSON), correct a row, start validation,
 * approve the apply, resume after a datastore pause, cancel, and read the job, its rows and its error file. Every state
 * change is a CAS on the job's version and is audited to {@code domain_events} with the acting admin. The background
 * {@link ImportJobWorker} does the validating and applying.
 */
public class ImportJobService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);
    static final String AGGREGATE = "import_job";

    private final ImportJobRepository repo;
    private final DomainAudit audit;
    private final Tx tx;
    private final ObjectMapper mapper;
    private final long maxRowsPerJob;
    private final int maxActiveJobs;

    public ImportJobService(ImportJobRepository repo, DomainAudit audit, Tx tx, ObjectMapper mapper, long maxRowsPerJob,
                            int maxActiveJobs) {
        this.repo = Objects.requireNonNull(repo);
        this.audit = Objects.requireNonNull(audit);
        this.tx = Objects.requireNonNull(tx);
        this.mapper = Objects.requireNonNull(mapper);
        if (maxRowsPerJob < 1 || maxActiveJobs < 1) throw new IllegalArgumentException("import job limits must be >= 1");
        this.maxRowsPerJob = maxRowsPerJob;
        this.maxActiveJobs = maxActiveJobs;
    }

    /** The result of appending a file: rows added by this request, rows in the job, and rows this file flagged DUPLICATE. */
    public record Appended(long rowsAdded, long rowsTotal, long duplicates) { }

    public ImportJob create(String kind, String note, Actor actor) {
        ImportJob.Kind k;
        try {
            k = ImportJob.Kind.valueOf(String.valueOf(kind).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ImportJobException.invalid("kind must be one of " + List.of(ImportJob.Kind.values()));
        }
        if (note != null && note.length() > 500) throw ImportJobException.invalid("note must be at most 500 characters");
        if (repo.countActive() >= maxActiveJobs) {
            throw ImportJobException.conflict("too many active import jobs (max " + maxActiveJobs + "); cancel or complete one first");
        }
        ImportJob job = repo.create(k, note, actorOf(actor));
        audit(job.id(), "IMPORT_JOB_CREATED", Map.of("kind", k.name()), actor);
        return job;
    }

    public ImportJob require(String id) {
        ImportJob job = repo.find(id);
        if (job == null) throw ImportJobException.notFound(id);
        return job;
    }

    public List<ImportJob> list(String status, String afterId, int limit) {
        ImportJob.Status s = null;
        if (status != null && !status.isBlank()) {
            try {
                s = ImportJob.Status.valueOf(status.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ImportJobException.invalid("status must be one of " + List.of(ImportJob.Status.values()));
            }
        }
        return repo.list(s, afterId, Math.max(1, Math.min(limit, 200)));
    }

    /** Append a CSV file (streamed; never held in memory as a whole). The job must be OPEN and no other upload in progress. */
    public Appended appendCsv(String id, InputStream body) throws IOException {
        ImportJob job = requireOpen(id);
        String lock = lockAppend(job);
        long[] row = {job.rowsTotal()};
        long[] dup = {0};
        boolean kept;
        try {
            ImportCsvParser.parse(body, r -> {
                requireCapacity(row[0]);
                boolean d = appendRow(job.id(), row[0], r.line(), r.request());
                row[0]++;   // counted only once the row is stored, so rows_total never exceeds the stored rows
                if (d) dup[0]++;
            });
        } finally {
            kept = finishAppend(job, lock, row[0]);
        }
        if (!kept) throw jobLeftOpen();
        return new Appended(row[0] - job.rowsTotal(), row[0], dup[0]);
    }

    /** Append rows given as the JSON shape of {@code POST /api/v1/admin/imports/products}. The job must be OPEN. */
    public Appended appendRows(String id, List<CreateProductRequest> rows) {
        if (rows == null || rows.isEmpty()) throw ImportJobException.invalid("rows must contain at least one entry");
        ImportJob job = requireOpen(id);
        String lock = lockAppend(job);
        long row = job.rowsTotal();
        long dup = 0;
        boolean kept;
        try {
            for (int i = 0; i < rows.size(); i++) {
                requireCapacity(row);
                if (appendRow(job.id(), row, i + 1, rows.get(i))) dup++;
                row++;
            }
        } finally {
            kept = finishAppend(job, lock, row);
        }
        if (!kept) throw jobLeftOpen();
        return new Appended(row - job.rowsTotal(), row, dup);
    }

    static final long APPEND_LOCK_MS = 15 * 60_000L;

    /**
     * Publish the stored rows ({@code rows_total}) and release the lock. When the job left OPEN during the upload (a cancel,
     * or validation after the lock expired) the rows are not published but removed, and false is returned.
     */
    private boolean finishAppend(ImportJob job, String lock, long rowsNow) {
        try {
            if (rowsNow == job.rowsTotal()) return true;
            if (repo.setRowsTotal(job.id(), lock, rowsNow)) return true;
            repo.deleteRowsFrom(job.id(), job.rowsTotal());
            return false;
        } finally {
            repo.unlockAppend(job.id(), lock);
        }
    }

    private static ImportJobException jobLeftOpen() {
        return ImportJobException.conflict("the job left OPEN during the upload; none of its rows were added");
    }

    private String lockAppend(ImportJob job) {
        String token = ImportJobRepository.newLeaseToken();
        if (!repo.lockAppend(job.id(), token, APPEND_LOCK_MS)) {
            throw ImportJobException.conflict("another upload to this job is in progress; wait for it to finish");
        }
        return token;
    }

    /** Replace one row's payload (a correction after REJECTED). The job must be OPEN or REJECTED; REJECTED goes back to OPEN. */
    public ImportJob correctRow(String id, long row, CreateProductRequest request, Actor actor) {
        ImportJob job = require(id);
        if (job.status() != ImportJob.Status.OPEN && job.status() != ImportJob.Status.REJECTED) {
            throw ImportJobException.conflict("rows can be corrected only while the job is OPEN or REJECTED (it is " + job.status() + ")");
        }
        if (row < 0 || row >= job.rowsTotal()) throw ImportJobException.invalid("row must be 0.." + (job.rowsTotal() - 1));
        if (request == null) throw ImportJobException.invalid("a product row is required");
        if (job.status() == ImportJob.Status.REJECTED) job = reopen(job, actor);
        try {
            repo.replaceRowPayload(job.id(), row, toPayload(request), dedupKey(request), identityKeys(request));
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getError().getCode() != 11000) throw e;
            throw ImportJobException.invalid("the product id, a GTIN or the internal key of this row already belongs to another row of this job");
        }
        return job;
    }

    /** REJECTED → OPEN: validation verdicts are cleared so the next pass starts clean. */
    public ImportJob reopen(ImportJob job, Actor actor) {
        ImportJob next = repo.transition(job.id(), job.version(), List.of(ImportJob.Status.REJECTED), ImportJob.Status.OPEN, true, null);
        if (next == null) throw ImportJobException.conflict("the job changed; reload it and retry");
        repo.clearValidation(job.id());
        audit(job.id(), "IMPORT_JOB_REOPENED", Map.of(), actor);
        return next;
    }

    /** OPEN or REJECTED → VALIDATING (REJECTED is re-validated from scratch). */
    public ImportJob validate(String id, Long expectedVersion, Actor actor) {
        ImportJob job = require(id);
        if (job.rowsTotal() == 0) throw ImportJobException.invalid("the job has no rows");
        long v = expectedVersion == null ? job.version() : expectedVersion;
        ImportJob next = repo.transition(id, v, List.of(ImportJob.Status.OPEN, ImportJob.Status.REJECTED), ImportJob.Status.VALIDATING,
                true, null, repo.noAppendInProgress());
        if (next == null) throw stateConflict(job, "validation can start only from OPEN or REJECTED, with no upload in progress");
        if (job.status() == ImportJob.Status.REJECTED) repo.clearValidation(job.id());   // after the CAS: a stale call wipes nothing
        audit(id, "IMPORT_JOB_VALIDATION_STARTED", Map.of("rows", job.rowsTotal()), actor);
        return next;
    }

    /** VALIDATED → APPLYING: the explicit approval; the approver is recorded and attributes every applied row. */
    public ImportJob apply(String id, Long expectedVersion, Actor actor) {
        ImportJob job = require(id);
        long v = expectedVersion == null ? job.version() : expectedVersion;
        ImportJob next = repo.transition(id, v, List.of(ImportJob.Status.VALIDATED), ImportJob.Status.APPLYING, true,
                com.mongodb.client.model.Updates.set("approved_by", ImportJobRepository.actorDoc(actorOf(actor))));
        if (next == null) throw stateConflict(job, "only a VALIDATED job can be applied");
        audit(id, "IMPORT_JOB_APPROVED", Map.of("rows", job.rowsTotal(), "valid", job.counts().valid(),
                "unchanged", job.counts().unchanged()), actor);
        return next;
    }

    /** PAUSED → APPLYING from the saved cursor (nothing is re-applied). */
    public ImportJob resume(String id, Long expectedVersion, Actor actor) {
        ImportJob job = require(id);
        long v = expectedVersion == null ? job.version() : expectedVersion;
        ImportJob next = repo.transition(id, v, List.of(ImportJob.Status.PAUSED), ImportJob.Status.APPLYING, false, null);
        if (next == null) throw stateConflict(job, "only a PAUSED job can be resumed");
        audit(id, "IMPORT_JOB_RESUMED", Map.of("next_row", job.nextRow()), actor);
        return next;
    }

    /** Any non-terminal state → CANCELLED; a working job loses its lease and the worker stops at the next row. */
    public ImportJob cancel(String id, Long expectedVersion, Actor actor) {
        ImportJob job = require(id);
        long v = expectedVersion == null ? job.version() : expectedVersion;
        List<ImportJob.Status> from = new ArrayList<>();
        for (ImportJob.Status s : ImportJob.Status.values()) if (!s.terminal()) from.add(s);
        ImportJob next = repo.transition(id, v, from, ImportJob.Status.CANCELLED, false, null);
        if (next == null) throw stateConflict(job, "the job is already " + job.status());
        audit(id, "IMPORT_JOB_CANCELLED", Map.of("from", job.status().name(), "next_row", job.nextRow()), actor);
        return next;
    }

    public List<Document> rows(String id, long from, int limit) {
        require(id);
        return repo.page(id, Math.max(0, from), Math.max(1, Math.min(limit, 500)));
    }

    /** Every row with a negative verdict, in row order, as a CSV with the row's product id and the reason. */
    public void writeErrorsCsv(String id, Appendable out) throws IOException {
        require(id);
        out.append("row,line,id,phase,outcome,code,message\r\n");
        for (String outcome : List.of("INVALID", "DUPLICATE", "FAILED")) {
            long after = -1;
            while (true) {
                List<Document> page = repo.byOutcome(id, outcome, after, 500);
                if (page.isEmpty()) break;
                for (Document r : page) {
                    Document v = r.get("validation", Document.class);
                    Document a = r.get("apply", Document.class);
                    Document o = a != null && outcome.equals(a.getString("outcome")) ? a : v;
                    String phase = o == a ? "apply" : "validation";
                    Document payload = r.get("payload", Document.class);
                    out.append(String.valueOf(r.getLong("row"))).append(',').append(String.valueOf(r.getInteger("line", 0))).append(',')
                            .append(csv(payload == null ? "" : payload.getString("id"))).append(',').append(phase).append(',')
                            .append(outcome).append(',').append(csv(o.getString("code"))).append(',').append(csv(o.getString("message")))
                            .append("\r\n");
                    after = r.getLong("row");
                }
            }
        }
    }

    static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ---------------------------------------------------------------- internals

    private ImportJob requireOpen(String id) {
        ImportJob job = require(id);
        if (job.status() != ImportJob.Status.OPEN) {
            throw ImportJobException.conflict("rows can be added only while the job is OPEN (it is " + job.status() + ")");
        }
        return job;
    }

    private void requireCapacity(long row) {
        if (row >= maxRowsPerJob) throw ImportJobException.invalid("a job holds at most " + maxRowsPerJob + " rows");
    }

    /** @return true when the row was a duplicate (product id, GTIN or internal key) of an earlier row in the job */
    private boolean appendRow(String jobId, long row, int line, CreateProductRequest request) {
        return repo.appendRow(jobId, row, line, toPayload(request), dedupKey(request), identityKeys(request));
    }

    static String dedupKey(CreateProductRequest r) {
        return r.id() == null || r.id().isBlank() ? null : r.id().trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The identities the validator would also reject as duplicates inside one batch, prefixed: every GTIN, and the internal
     * key only for an internal-identity row (the catalogue ignores the internal key of a GTIN-identity product).
     */
    static List<String> identityKeys(CreateProductRequest r) {
        List<String> keys = new ArrayList<>();
        if (r.gtins() != null) {
            for (var g : r.gtins()) if (g != null && g.value() != null && !g.value().isBlank()) keys.add("gtin:" + g.value().trim());
        }
        if ("internal".equals(r.identityType()) && r.internalKey() != null && !r.internalKey().isBlank()) {
            keys.add("key:" + r.internalKey().trim());
        }
        return keys.stream().distinct().toList();
    }

    Document toPayload(CreateProductRequest r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = mapper.convertValue(r, LinkedHashMap.class);
        return new Document(m);
    }

    CreateProductRequest fromPayload(Document d) {
        return mapper.convertValue(d, CreateProductRequest.class);
    }

    private static ImportJob.Actor actorOf(Actor a) {
        return new ImportJob.Actor(a.type().name(), a.id(), a.credentialId(), a.requestId());
    }

    private static ImportJobException stateConflict(ImportJob job, String rule) {
        return ImportJobException.conflict(rule + " (the job is " + job.status() + ", version " + job.version() + ")");
    }

    private void audit(String id, String type, Map<String, Object> detail, Actor actor) {
        try {
            tx.run(s -> audit.append(s, new DomainEvent(AGGREGATE, id, type, new LinkedHashMap<>(detail), actor)));
        } catch (RuntimeException e) {
            // the job document is the record; the event is the admin trail — never fail the request for it
            log.warn("import_job_audit_failed job={} type={} error={}", id, type, e.getClass().getSimpleName());
        }
    }
}
