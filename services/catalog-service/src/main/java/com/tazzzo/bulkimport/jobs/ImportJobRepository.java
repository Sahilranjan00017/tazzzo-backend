package com.tazzzo.bulkimport.jobs;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence of {@link ImportJob}s ({@code import_jobs}, one document per job, CAS on {@code version}) and their rows
 * ({@code import_rows}, one document per row: the submitted payload and the outcome of each phase). Row-level
 * uniqueness inside a job is DB-enforced: {@code (job_id, row)} and, for rows that carry one, {@code (job_id, dedup_key)}
 * (the product id), so a duplicate across two appended files is caught at ingestion, not after.
 */
public class ImportJobRepository {

    public static final String JOBS = "import_jobs";
    public static final String ROWS = "import_rows";
    static final String ID_PREFIX = "IMPJ-";
    static final int ERROR_SAMPLE = 50;

    private final MongoDatabase db;
    private final Clock clock;

    public ImportJobRepository(MongoDatabase db, Clock clock) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
    }

    private MongoCollection<Document> jobs() {
        return db.getCollection(JOBS);
    }

    private MongoCollection<Document> rows() {
        return db.getCollection(ROWS);
    }

    // ---------------------------------------------------------------- jobs

    public ImportJob create(ImportJob.Kind kind, String note, ImportJob.Actor by) {
        Instant now = clock.instant();
        String id = ID_PREFIX + new ObjectId().toHexString();
        Document d = new Document("_id", id).append("kind", kind.name()).append("status", ImportJob.Status.OPEN.name())
                .append("note", note).append("created_by", actorDoc(by)).append("rows_total", 0L)
                .append("counts", countsDoc(ImportJob.Counts.ZERO)).append("next_row", 0L).append("attempt_count", 0)
                .append("version", 1L).append("created_at", Date.from(now)).append("updated_at", Date.from(now));
        jobs().insertOne(d);
        return toJob(d);
    }

    public ImportJob find(String id) {
        Document d = id == null || !id.startsWith(ID_PREFIX) ? null : jobs().find(Filters.eq("_id", id)).first();
        return d == null ? null : toJob(d);
    }

    /** Newest first; {@code status} optional; keyset by {@code afterId} on the descending _id order. */
    public List<ImportJob> list(ImportJob.Status status, String afterId, int limit) {
        List<Bson> f = new ArrayList<>();
        if (status != null) f.add(Filters.eq("status", status.name()));
        if (afterId != null) f.add(Filters.lt("_id", afterId));
        List<ImportJob> out = new ArrayList<>();
        for (Document d : jobs().find(f.isEmpty() ? Filters.empty() : Filters.and(f)).sort(Sorts.descending("_id")).limit(limit)) {
            out.add(toJob(d));
        }
        return out;
    }

    public long countActive() {
        return jobs().countDocuments(Filters.in("status", ImportJob.Status.OPEN.name(), ImportJob.Status.VALIDATING.name(),
                ImportJob.Status.VALIDATED.name(), ImportJob.Status.REJECTED.name(), ImportJob.Status.APPLYING.name(),
                ImportJob.Status.PAUSED.name()));
    }

    /**
     * A status transition guarded by the job's version (CAS) and by the states it may leave from; resets the cursor when a
     * new phase starts. Returns the updated job, or null when the CAS lost (stale version or a state change in between).
     */
    public ImportJob transition(String id, long expectedVersion, List<ImportJob.Status> from, ImportJob.Status to, boolean resetCursor,
                                Bson extra) {
        Instant now = clock.instant();
        List<Bson> sets = new ArrayList<>(List.of(Updates.set("status", to.name()), Updates.set("updated_at", Date.from(now)),
                Updates.inc("version", 1L)));
        if (resetCursor) sets.add(Updates.set("next_row", 0L));
        sets.add(Updates.unset("lease_token"));
        sets.add(Updates.unset("lease_until"));
        if (to == ImportJob.Status.VALIDATING) sets.add(Updates.set("counts", countsDoc(ImportJob.Counts.ZERO)));
        if (to == ImportJob.Status.APPLYING) sets.add(Updates.set("started_at", Date.from(now)));
        if (to.terminal()) sets.add(Updates.set("finished_at", Date.from(now)));
        if (extra != null) sets.add(extra);
        Document d = jobs().findOneAndUpdate(Filters.and(Filters.eq("_id", id), Filters.eq("version", expectedVersion),
                        Filters.in("status", from.stream().map(Enum::name).toList())),
                Updates.combine(sets), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return d == null ? null : toJob(d);
    }

    /** The same transition for the worker, which holds the lease rather than a client version. */
    public ImportJob finishPhase(String id, String leaseToken, ImportJob.Status to, String lastError) {
        Instant now = clock.instant();
        List<Bson> sets = new ArrayList<>(List.of(Updates.set("status", to.name()), Updates.set("updated_at", Date.from(now)),
                Updates.inc("version", 1L), Updates.unset("lease_token"), Updates.unset("lease_until")));
        if (to.terminal()) sets.add(Updates.set("finished_at", Date.from(now)));
        sets.add(lastError == null ? Updates.unset("last_error") : Updates.set("last_error", lastError));
        Document d = jobs().findOneAndUpdate(Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)),
                Updates.combine(sets), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return d == null ? null : toJob(d);
    }

    /** Claim the oldest job in a working state whose lease is free or expired. Null when there is nothing to do. */
    public ImportJob claim(String leaseToken, long leaseMs) {
        Instant now = clock.instant();
        Document d = jobs().findOneAndUpdate(Filters.and(
                        Filters.in("status", ImportJob.Status.VALIDATING.name(), ImportJob.Status.APPLYING.name()),
                        Filters.or(Filters.exists("lease_until", false), Filters.lt("lease_until", Date.from(now)))),
                Updates.combine(Updates.set("lease_token", leaseToken), Updates.set("lease_until", Date.from(now.plusMillis(leaseMs))),
                        Updates.inc("attempt_count", 1), Updates.set("updated_at", Date.from(now))),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("updated_at")).returnDocument(ReturnDocument.AFTER));
        return d == null ? null : toJob(d);
    }

    /** Advance the cursor and the counters under the lease; false when the lease was lost (another worker took over). */
    public boolean progress(String id, String leaseToken, long nextRow, ImportJob.Counts delta, long leaseMs) {
        Instant now = clock.instant();
        List<Bson> u = new ArrayList<>(List.of(Updates.set("next_row", nextRow), Updates.set("updated_at", Date.from(now)),
                Updates.set("lease_until", Date.from(now.plusMillis(leaseMs)))));
        if (delta.valid() != 0) u.add(Updates.inc("counts.valid", delta.valid()));
        if (delta.unchanged() != 0) u.add(Updates.inc("counts.unchanged", delta.unchanged()));
        if (delta.invalid() != 0) u.add(Updates.inc("counts.invalid", delta.invalid()));
        if (delta.duplicate() != 0) u.add(Updates.inc("counts.duplicate", delta.duplicate()));
        if (delta.applied() != 0) u.add(Updates.inc("counts.applied", delta.applied()));
        if (delta.failed() != 0) u.add(Updates.inc("counts.failed", delta.failed()));
        if (delta.notAttempted() != 0) u.add(Updates.inc("counts.not_attempted", delta.notAttempted()));
        return jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)), Updates.combine(u))
                .getModifiedCount() == 1;
    }

    public boolean releaseLease(String id, String leaseToken) {
        return jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)),
                Updates.combine(Updates.unset("lease_token"), Updates.unset("lease_until"))).getModifiedCount() == 1;
    }

    // ---------------------------------------------------------------- rows

    /**
     * Append one row. {@code dedupKey} (the product id) is unique within the job: a second row with the same key is stored
     * WITHOUT the key and already marked DUPLICATE, so the job keeps every submitted line and its verdict. Returns true
     * when the row was such a duplicate. The caller holds the job in OPEN state and bumps {@code rows_total}.
     */
    public boolean appendRow(String jobId, long row, int line, Document payload, String dedupKey) {
        Instant now = clock.instant();
        Document d = new Document("_id", rowId(jobId, row)).append("job_id", jobId).append("row", row).append("line", line)
                .append("payload", payload).append("created_at", Date.from(now));
        if (dedupKey != null && !dedupKey.isBlank()) d.append("dedup_key", dedupKey);
        try {
            rows().insertOne(d);
            return false;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() != 11000 || dedupKey == null) throw e;
            d.remove("dedup_key");
            d.append("validation", outcomeDoc("DUPLICATE", "DUPLICATE_ROW", "product id " + dedupKey + " appears earlier in this job"));
            rows().insertOne(d);
            return true;
        }
    }

    public void setRowsTotal(String jobId, long rowsTotal) {
        jobs().updateOne(Filters.eq("_id", jobId), Updates.combine(Updates.set("rows_total", rowsTotal),
                Updates.set("updated_at", Date.from(clock.instant()))));
    }

    /** Rows {@code [from, from + limit)} in row order. */
    public List<Document> page(String jobId, long from, int limit) {
        return rows().find(Filters.and(Filters.eq("job_id", jobId), Filters.gte("row", from))).sort(Sorts.ascending("row"))
                .limit(limit).into(new ArrayList<>());
    }

    /** Rows whose outcome (in either phase) is {@code outcome}, keyset by row. */
    public List<Document> byOutcome(String jobId, String outcome, long afterRow, int limit) {
        return rows().find(Filters.and(Filters.eq("job_id", jobId), Filters.gt("row", afterRow),
                        Filters.or(Filters.eq("validation.outcome", outcome), Filters.eq("apply.outcome", outcome))))
                .sort(Sorts.ascending("row")).limit(limit).into(new ArrayList<>());
    }

    public void setValidation(String jobId, long row, String outcome, String code, String message) {
        rows().updateOne(Filters.eq("_id", rowId(jobId, row)), Updates.set("validation", outcomeDoc(outcome, code, message)));
    }

    public void setApply(String jobId, long row, String outcome, Long version, String code, String message) {
        Document o = outcomeDoc(outcome, code, message);
        if (version != null) o.append("version", version);
        rows().updateOne(Filters.eq("_id", rowId(jobId, row)), Updates.set("apply", o));
    }

    /** Clear the validation verdicts (a correction re-opened the job) so the next validation pass starts clean. */
    public void clearValidation(String jobId) {
        rows().updateMany(Filters.and(Filters.eq("job_id", jobId), Filters.exists("dedup_key", true)), Updates.unset("validation"));
    }

    public void replaceRowPayload(String jobId, long row, Document payload, String dedupKey) {
        List<Bson> u = new ArrayList<>(List.of(Updates.set("payload", payload), Updates.unset("validation"), Updates.unset("apply")));
        u.add(dedupKey == null ? Updates.unset("dedup_key") : Updates.set("dedup_key", dedupKey));
        rows().updateOne(Filters.eq("_id", rowId(jobId, row)), Updates.combine(u));
    }

    public long deleteRows(String jobId) {
        return rows().deleteMany(Filters.eq("job_id", jobId)).getDeletedCount();
    }

    // ---------------------------------------------------------------- mapping

    static String rowId(String jobId, long row) {
        return jobId + ":" + String.format("%09d", row);
    }

    static Document outcomeDoc(String outcome, String code, String message) {
        Document d = new Document("outcome", outcome);
        if (code != null) d.append("code", code);
        if (message != null) d.append("message", message.length() > 500 ? message.substring(0, 500) : message);
        return d;
    }

    static Document actorDoc(ImportJob.Actor a) {
        return a == null ? null : new Document("type", a.type()).append("id", a.id()).append("credential_id", a.credentialId())
                .append("request_id", a.requestId());
    }

    static ImportJob.Actor toActor(Document d) {
        return d == null ? null : new ImportJob.Actor(d.getString("type"), d.getString("id"), d.getString("credential_id"),
                d.getString("request_id"));
    }

    static Document countsDoc(ImportJob.Counts c) {
        return new Document("valid", c.valid()).append("unchanged", c.unchanged()).append("invalid", c.invalid())
                .append("duplicate", c.duplicate()).append("applied", c.applied()).append("failed", c.failed())
                .append("not_attempted", c.notAttempted());
    }

    static ImportJob.Counts toCounts(Document d) {
        if (d == null) return ImportJob.Counts.ZERO;
        return new ImportJob.Counts(num(d, "valid"), num(d, "unchanged"), num(d, "invalid"), num(d, "duplicate"), num(d, "applied"),
                num(d, "failed"), num(d, "not_attempted"));
    }

    private static long num(Document d, String k) {
        Object v = d.get(k);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    static ImportJob toJob(Document d) {
        return new ImportJob(d.getString("_id"), ImportJob.Kind.valueOf(d.getString("kind")), ImportJob.Status.valueOf(d.getString("status")),
                d.getString("note"), toActor(d.get("created_by", Document.class)), toActor(d.get("approved_by", Document.class)),
                num(d, "rows_total"), toCounts(d.get("counts", Document.class)), num(d, "next_row"), (int) num(d, "attempt_count"),
                d.getString("last_error"), instant(d, "created_at"), instant(d, "updated_at"), instant(d, "started_at"),
                instant(d, "finished_at"), num(d, "version"));
    }

    private static Instant instant(Document d, String k) {
        Date v = d.getDate(k);
        return v == null ? null : v.toInstant();
    }

    static String newLeaseToken() {
        return UUID.randomUUID().toString();
    }
}
