package com.tazzzo.bulkimport.jobs;

import java.time.Instant;
import java.util.Map;

/**
 * One asynchronous, resumable catalogue import: rows are appended in any number of requests (streamed CSV or JSON),
 * validated by a background worker in bounded batches, approved explicitly, then applied in bounded batches by the same
 * worker from a row cursor that survives restarts. Every row keeps its own outcome in {@code import_rows}, so a job can
 * be resumed, its failed rows exported and corrected, and a re-run never duplicates a product (an applied row is UNCHANGED
 * on re-apply through the existing validator).
 *
 * <p>Status: {@code OPEN} (accepting rows) → {@code VALIDATING} → {@code VALIDATED} (every row valid; the dry-run report)
 * or {@code REJECTED} (some rows invalid; fix and re-validate) → {@code APPLYING} (after explicit approval) → {@code COMPLETED}
 * or {@code PAUSED} (the datastore failed mid-run; resumable from the cursor) · {@code CANCELLED} from any non-terminal state.
 */
public record ImportJob(String id, Kind kind, Status status, String note, Actor createdBy, Actor approvedBy, long rowsTotal,
                        Counts counts, long nextRow, int attemptCount, String lastError, Instant createdAt, Instant updatedAt,
                        Instant startedAt, Instant finishedAt, long version) {

    public enum Kind { PRODUCTS }

    public enum Status {
        OPEN, VALIDATING, VALIDATED, REJECTED, APPLYING, PAUSED, COMPLETED, CANCELLED;

        public boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }

        /** A state the worker owns: it claims the job and advances {@code nextRow}. */
        public boolean working() {
            return this == VALIDATING || this == APPLYING;
        }
    }

    /** Row outcomes per phase. Validation: VALID, UNCHANGED, INVALID, DUPLICATE. Apply: APPLIED, UNCHANGED, FAILED, NOT_ATTEMPTED. */
    public record Counts(long valid, long unchanged, long invalid, long duplicate, long applied, long failed, long notAttempted) {
        public static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0, 0);

        public Map<String, Long> asMap() {
            return Map.of("valid", valid, "unchanged", unchanged, "invalid", invalid, "duplicate", duplicate,
                    "applied", applied, "failed", failed, "not_attempted", notAttempted);
        }
    }

    /** The admin who did it: the audit actor's bounded identity fields (never a credential) and the request it came in on. */
    public record Actor(String type, String id, String credentialId, String requestId) { }
}
