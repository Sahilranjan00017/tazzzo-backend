package com.tazzzo.bulkimport.jobs;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOneModel;
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
    private com.tazzzo.bulkimport.ImportMetrics metrics = com.tazzzo.bulkimport.ImportMetrics.unregistered();

    /** Records every job status transition this repository performs (set once, at wiring; fixtures keep a private no-op sink). */
    public ImportJobRepository withMetrics(com.tazzzo.bulkimport.ImportMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        return this;
    }

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
                .append("version", 1L).append("lease_until", new Date(0)).append("created_at", Date.from(now)).append("updated_at", Date.from(now));
        jobs().insertOne(d);
        metrics.jobTransition(ImportJob.Status.OPEN);
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
        return transition(id, expectedVersion, from, to, resetCursor, extra, null);
    }

    /** The same, with an extra precondition on the job document ({@code guard}, null for none). */
    public ImportJob transition(String id, long expectedVersion, List<ImportJob.Status> from, ImportJob.Status to, boolean resetCursor,
                                Bson extra, Bson guard) {
        Instant now = clock.instant();
        List<Bson> sets = new ArrayList<>(List.of(Updates.set("status", to.name()), Updates.set("updated_at", Date.from(now)),
                Updates.inc("version", 1L)));
        if (resetCursor) sets.add(Updates.set("next_row", 0L));
        sets.add(Updates.unset("lease_token"));
        sets.add(Updates.set("lease_until", new Date(0)));   // always present: the claim scan is a range on it
        if (to != ImportJob.Status.OPEN) {
            // the append lock dies with the OPEN state: an upload still running can no longer publish its rows
            sets.add(Updates.unset("append_lock_token"));
            sets.add(Updates.unset("append_lock_until"));
        }
        if (to == ImportJob.Status.VALIDATING) sets.add(Updates.set("counts", countsDoc(ImportJob.Counts.ZERO)));
        if (to == ImportJob.Status.APPLYING) sets.add(Updates.set("started_at", Date.from(now)));
        if (to.terminal()) sets.add(Updates.set("finished_at", Date.from(now)));
        if (extra != null) sets.add(extra);
        List<Bson> f = new ArrayList<>(List.of(Filters.eq("_id", id), Filters.eq("version", expectedVersion),
                Filters.in("status", from.stream().map(Enum::name).toList())));
        if (guard != null) f.add(guard);
        Document d = jobs().findOneAndUpdate(Filters.and(f), Updates.combine(sets),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (d != null) metrics.jobTransition(to);
        return d == null ? null : toJob(d);
    }

    /** No upload holds the job's append lock (never taken, released, or expired). */
    public Bson noAppendInProgress() {
        return Filters.or(Filters.exists("append_lock_until", false), Filters.lt("append_lock_until", Date.from(clock.instant())));
    }

    /** The same transition for the worker, which holds the lease rather than a client version. */
    public ImportJob finishPhase(String id, String leaseToken, ImportJob.Status to, String lastError) {
        Instant now = clock.instant();
        List<Bson> sets = new ArrayList<>(List.of(Updates.set("status", to.name()), Updates.set("updated_at", Date.from(now)),
                Updates.inc("version", 1L), Updates.unset("lease_token"), Updates.set("lease_until", new Date(0))));
        if (to.terminal()) sets.add(Updates.set("finished_at", Date.from(now)));
        sets.add(lastError == null ? Updates.unset("last_error") : Updates.set("last_error", lastError));
        Document d = jobs().findOneAndUpdate(Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)),
                Updates.combine(sets), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (d != null) metrics.jobTransition(to);
        return d == null ? null : toJob(d);
    }

    /** Claim the oldest job in a working state whose lease is free or expired. Null when there is nothing to do. */
    public ImportJob claim(String leaseToken, long leaseMs) {
        Instant now = clock.instant();
        Document d = jobs().findOneAndUpdate(Filters.and(
                        Filters.in("status", ImportJob.Status.VALIDATING.name(), ImportJob.Status.APPLYING.name()),
                        Filters.lt("lease_until", Date.from(now))),
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
        u.addAll(countUpdates(delta));
        return jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)), Updates.combine(u))
                .getMatchedCount() == 1;
    }

    private static List<Bson> countUpdates(ImportJob.Counts delta) {
        List<Bson> u = new ArrayList<>();
        if (delta.valid() != 0) u.add(Updates.inc("counts.valid", delta.valid()));
        if (delta.unchanged() != 0) u.add(Updates.inc("counts.unchanged", delta.unchanged()));
        if (delta.invalid() != 0) u.add(Updates.inc("counts.invalid", delta.invalid()));
        if (delta.duplicate() != 0) u.add(Updates.inc("counts.duplicate", delta.duplicate()));
        if (delta.applied() != 0) u.add(Updates.inc("counts.applied", delta.applied()));
        if (delta.failed() != 0) u.add(Updates.inc("counts.failed", delta.failed()));
        if (delta.notAttempted() != 0) u.add(Updates.inc("counts.not_attempted", delta.notAttempted()));
        return u;
    }

    public boolean releaseLease(String id, String leaseToken) {
        return jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)),
                Updates.combine(Updates.unset("lease_token"), Updates.set("lease_until", new Date(0)))).getMatchedCount() == 1;
    }

    /** Extend the lease; false when it was lost (expired and re-claimed, or the job was cancelled). */
    public boolean renewLease(String id, String leaseToken, long leaseMs) {
        Instant now = clock.instant();
        return jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)),
                Updates.combine(Updates.set("lease_until", Date.from(now.plusMillis(leaseMs))), Updates.set("updated_at", Date.from(now))))
                .getMatchedCount() == 1;
    }

    /**
     * One row's apply verdict and the job's cursor/counters, in ONE transaction guarded by the lease: either both land or
     * neither does. A worker whose lease was lost after it minted a row therefore records nothing — the next holder re-reads
     * the row and finds the product UNCHANGED. Returns false when the lease was lost.
     */
    public boolean recordApply(String id, String leaseToken, long row, String outcome, String code, String message, long nextRow,
                               ImportJob.Counts delta, long leaseMs, com.tazzzo.catalog.tx.Tx tx) {
        try {
            return tx.call(session -> {
                Instant now = clock.instant();
                List<Bson> u = new ArrayList<>(List.of(Updates.set("next_row", nextRow), Updates.set("updated_at", Date.from(now)),
                        Updates.set("lease_until", Date.from(now.plusMillis(leaseMs)))));
                u.addAll(countUpdates(delta));
                long n = jobs().updateOne(session, Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)), Updates.combine(u))
                        .getMatchedCount();
                if (n != 1) throw new LeaseLost();
                rows().updateOne(session, Filters.eq("_id", rowId(id, row)), Updates.set("apply", outcomeDoc(outcome, code, message)));
                return true;
            });
        } catch (LeaseLost e) {
            return false;
        }
    }

    /** One row's validation verdict. */
    public record Verdict(long row, String outcome, String code, String message) {
    }

    /**
     * A validation batch's verdicts and the job's cursor/counters, in ONE transaction guarded by the lease: a worker that
     * lost its lease writes no verdict over the new holder's. Returns false when the lease was lost.
     */
    public boolean recordValidation(String id, String leaseToken, List<Verdict> verdicts, long nextRow, ImportJob.Counts delta, long leaseMs,
                                    com.tazzzo.catalog.tx.Tx tx) {
        try {
            return tx.call(session -> {
                Instant now = clock.instant();
                List<Bson> u = new ArrayList<>(List.of(Updates.set("next_row", nextRow), Updates.set("updated_at", Date.from(now)),
                        Updates.set("lease_until", Date.from(now.plusMillis(leaseMs)))));
                u.addAll(countUpdates(delta));
                long n = jobs().updateOne(session, Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)), Updates.combine(u))
                        .getMatchedCount();
                if (n != 1) throw new LeaseLost();
                if (!verdicts.isEmpty()) {
                    List<UpdateOneModel<Document>> w = new ArrayList<>(verdicts.size());
                    for (Verdict v : verdicts) {
                        w.add(new UpdateOneModel<>(Filters.eq("_id", rowId(id, v.row())),
                                Updates.set("validation", outcomeDoc(v.outcome(), v.code(), v.message()))));
                    }
                    rows().bulkWrite(session, w);
                }
                return true;
            });
        } catch (LeaseLost e) {
            return false;
        }
    }

    /**
     * The datastore failed while applying {@code row}: in ONE lease-guarded transaction the row is marked NOT_ATTEMPTED,
     * the cursor parks on it and the job goes PAUSED. A worker that lost its lease meanwhile writes nothing (the new holder
     * may already have applied that row). Returns false when the lease was lost.
     */
    public boolean recordPause(String id, String leaseToken, long row, String reason, com.tazzzo.catalog.tx.Tx tx) {
        try {
            return tx.call(session -> {
                Instant now = clock.instant();
                long n = jobs().updateOne(session, Filters.and(Filters.eq("_id", id), Filters.eq("lease_token", leaseToken)),
                        Updates.combine(Updates.set("status", ImportJob.Status.PAUSED.name()), Updates.set("next_row", row),
                                Updates.set("updated_at", Date.from(now)), Updates.inc("version", 1L), Updates.unset("lease_token"),
                                Updates.set("lease_until", new Date(0)), Updates.set("last_error", reason))).getMatchedCount();
                if (n != 1) throw new LeaseLost();
                rows().updateOne(session, Filters.eq("_id", rowId(id, row)),
                        Updates.set("apply", outcomeDoc("NOT_ATTEMPTED", "UNAVAILABLE", "the datastore failed; the job paused here")));
                return true;
            });
        } catch (LeaseLost e) {
            return false;
        }
    }

    /** Thrown inside the transaction to abort it when the lease is gone. */
    static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super("lease lost", null, false, false);
        }
    }

    /**
     * Take the job's append lock (one upload at a time per job, so row numbers are assigned by exactly one request).
     * Returns false when another upload holds it; the lock dies with the job's OPEN state and after {@code lockMs}.
     */
    public boolean lockAppend(String id, String token, long lockMs) {
        Instant now = clock.instant();
        return jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("status", ImportJob.Status.OPEN.name()),
                        Filters.or(Filters.exists("append_lock_until", false), Filters.lt("append_lock_until", Date.from(now)))),
                Updates.combine(Updates.set("append_lock_token", token), Updates.set("append_lock_until", Date.from(now.plusMillis(lockMs)))))
                .getMatchedCount() == 1;
    }

    /** Extend the upload's append lock; false when it no longer holds it (the job left OPEN, or another uploader took over). */
    public boolean renewAppendLock(String id, String token, long lockMs) {
        Instant now = clock.instant();
        return jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("status", ImportJob.Status.OPEN.name()),
                        Filters.eq("append_lock_token", token)),
                Updates.set("append_lock_until", Date.from(now.plusMillis(lockMs)))).getMatchedCount() == 1;
    }

    /** Bump the job's version (a correction changed its content) while holding the append lock. */
    public void touchUnderLock(String id, String token) {
        jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("append_lock_token", token)),
                Updates.combine(Updates.inc("version", 1L), Updates.set("updated_at", Date.from(clock.instant()))));
    }

    public void unlockAppend(String id, String token) {
        jobs().updateOne(Filters.and(Filters.eq("_id", id), Filters.eq("append_lock_token", token)),
                Updates.combine(Updates.unset("append_lock_token"), Updates.unset("append_lock_until")));
    }

    // ---------------------------------------------------------------- rows

    /**
     * Append one row. {@code dedupKey} (the product id) and every {@code identityKey} (a GTIN or an internal key, prefixed)
     * are unique within the job — DB-enforced by two partial unique indexes, so a duplicate is caught across appends and
     * across validation batches, not only inside one. A duplicate row is stored WITHOUT its keys and already marked
     * DUPLICATE, so the job keeps every submitted line and its verdict. Returns true for such a row. The caller holds the
     * job's append lock and bumps {@code rows_total} by the rows it stored.
     */
    public boolean appendRow(String jobId, long row, int line, Document payload, String dedupKey, List<String> identityKeys) {
        Instant now = clock.instant();
        Document d = new Document("_id", rowId(jobId, row)).append("job_id", jobId).append("row", row).append("line", line)
                .append("payload", payload).append("created_at", Date.from(now));
        if (dedupKey != null && !dedupKey.isBlank()) d.append("dedup_key", dedupKey);
        if (identityKeys != null && !identityKeys.isEmpty()) d.append("identity_keys", identityKeys);
        try {
            rows().insertOne(d);
            return false;
        } catch (MongoWriteException e) {
            String msg = String.valueOf(e.getError().getMessage());
            if (e.getError().getCode() != 11000 || !(msg.contains(DEDUP_INDEX) || msg.contains(IDENTITY_INDEX))) throw e;
            boolean identity = msg.contains(IDENTITY_INDEX);
            d.remove("dedup_key");
            d.remove("identity_keys");
            d.append("validation", identity
                    ? outcomeDoc("DUPLICATE", "DUPLICATE_IDENTITY", "a GTIN or internal key of this row belongs to an earlier row of this job")
                    : outcomeDoc("DUPLICATE", "DUPLICATE_ROW", "product id " + dedupKey + " appears earlier in this job"));
            rows().insertOne(d);
            return true;
        }
    }

    static final String DEDUP_INDEX = "import_rows_one_per_product";
    static final String IDENTITY_INDEX = "import_rows_one_per_identity";

    /**
     * Publish the rows an upload stored, only while the job is still OPEN and the upload still holds the append lock.
     * False when the job moved on meanwhile (cancelled, or the lock expired and validation started): the caller must then
     * remove the rows it stored, which no phase will ever read ({@link #page} stops at {@code rows_total}).
     */
    public boolean setRowsTotal(String jobId, String lockToken, long rowsTotal) {
        return jobs().updateOne(Filters.and(Filters.eq("_id", jobId), Filters.eq("status", ImportJob.Status.OPEN.name()),
                        Filters.eq("append_lock_token", lockToken)),
                Updates.combine(Updates.set("rows_total", rowsTotal), Updates.inc("version", 1L),
                        Updates.set("updated_at", Date.from(clock.instant()))))
                .getMatchedCount() == 1;
    }

    /** Remove an abandoned upload's rows: every row of the job numbered {@code fromRow} or later. */
    public long deleteRowsFrom(String jobId, long fromRow) {
        return rows().deleteMany(Filters.and(Filters.eq("job_id", jobId), Filters.gte("row", fromRow))).getDeletedCount();
    }

    /** Rows {@code [from, from + limit)} in row order. */
    public List<Document> page(String jobId, long from, int limit) {
        return rows().find(Filters.and(Filters.eq("job_id", jobId), Filters.gte("row", from))).sort(Sorts.ascending("row"))
                .limit(limit).into(new ArrayList<>());
    }

    /** The same, never past {@code below} (the job's {@code rows_total}): rows an unfinished upload stored are not read. */
    public List<Document> page(String jobId, long from, int limit, long below) {
        return rows().find(Filters.and(Filters.eq("job_id", jobId), Filters.gte("row", from), Filters.lt("row", below)))
                .sort(Sorts.ascending("row")).limit(limit).into(new ArrayList<>());
    }

    /** Rows whose outcome (in either phase) is {@code outcome}, keyset by row. */
    public List<Document> byOutcome(String jobId, String outcome, long afterRow, int limit) {
        return rows().find(Filters.and(Filters.eq("job_id", jobId), Filters.gt("row", afterRow),
                        Filters.or(Filters.eq("validation.outcome", outcome), Filters.eq("apply.outcome", outcome))))
                .sort(Sorts.ascending("row")).limit(limit).into(new ArrayList<>());
    }

    /**
     * Rows with a negative verdict (INVALID, DUPLICATE or FAILED, in either phase) after {@code afterRow}, in row order,
     * read in ONE pass over the job's rows with a server-side time bound.
     */
    public List<Document> negatives(String jobId, long afterRow, int limit, long maxTimeMs) {
        List<String> bad = List.of("INVALID", "DUPLICATE", "FAILED");
        return rows().find(Filters.and(Filters.eq("job_id", jobId), Filters.gt("row", afterRow),
                        Filters.or(Filters.in("validation.outcome", bad), Filters.in("apply.outcome", bad))))
                .sort(Sorts.ascending("row")).limit(limit).maxTime(maxTimeMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .into(new ArrayList<>());
    }

    public void replaceRowPayload(String jobId, long row, Document payload, String dedupKey, List<String> identityKeys) {
        List<Bson> u = new ArrayList<>(List.of(Updates.set("payload", payload), Updates.unset("validation"), Updates.unset("apply")));
        u.add(dedupKey == null ? Updates.unset("dedup_key") : Updates.set("dedup_key", dedupKey));
        u.add(identityKeys == null || identityKeys.isEmpty() ? Updates.unset("identity_keys") : Updates.set("identity_keys", identityKeys));
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
