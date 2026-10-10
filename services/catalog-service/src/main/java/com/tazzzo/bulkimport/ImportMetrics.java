package com.tazzzo.bulkimport;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Counters and timers for both import paths. Every tag value comes from a closed enum or a fixed word list in this class;
 * a caller cannot pass free text, so no job id, product id, file name, admin id or error message can ever become a tag.
 * Recording never throws into the import it observes. A component built without a registry (fixtures, older constructors)
 * records into a private in-memory registry, so call sites never null-check.
 *
 * <ul>
 *   <li>asynchronous jobs: {@code import_job_transitions{to}}, {@code import_rows_applied}, {@code import_rows_failed},
 *       {@code import_job_lease_lost{phase}}, {@code import_job_paused{reason}}, {@code import_worker_tick_duration{result}}</li>
 *   <li>synchronous bulk import: {@code bulk_import_runs{kind,outcome}}, {@code bulk_import_rows{kind,outcome}}</li>
 * </ul>
 */
public class ImportMetrics {

    private static final Logger log = LoggerFactory.getLogger(ImportMetrics.class);

    public static final String JOB_TRANSITIONS = "import_job_transitions";
    public static final String ROWS_APPLIED = "import_rows_applied";
    public static final String ROWS_FAILED = "import_rows_failed";
    public static final String LEASE_LOST = "import_job_lease_lost";
    public static final String PAUSED = "import_job_paused";
    public static final String TICK_DURATION = "import_worker_tick_duration";
    public static final String BULK_RUNS = "bulk_import_runs";
    public static final String BULK_ROWS = "bulk_import_rows";

    /** The worker phase a lease was lost in. */
    public enum Phase { VALIDATE, APPLY }

    /** Why a job went PAUSED (or, for a short ledger during validation, could not proceed). */
    public enum PauseReason { DATASTORE_FAILURE, LEDGER_SHORT, NO_APPROVER }

    /** How one worker tick ended. */
    public enum TickResult { IDLE, WORKED, PAUSED, ERROR, CLAIM_FAILED }

    /** The synchronous import kinds (the three {@code /api/v1/admin/imports/*} routes). */
    public enum BulkKind { PRICES, INVENTORY, PRODUCTS, OTHER;
        public static BulkKind of(String kind) {
            try {
                return valueOf(kind == null ? "" : kind.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return OTHER;
            }
        }
    }

    /** How one synchronous run ended. */
    public enum BulkOutcome { DRY_RUN, REJECTED, APPLIED, PARTIAL, FAILED, STOPPED, UNCHANGED }

    /** What happened to one row of a synchronous run. */
    public enum BulkRow { APPLIED, FAILED, UNCHANGED, NOT_ATTEMPTED, VALIDATED }

    public static final Set<String> ALLOWED_TAG_KEYS = Set.of("to", "phase", "reason", "result", "kind", "outcome");

    private final MeterRegistry registry;

    public ImportMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /** A private in-memory instance for components built without wiring. */
    public static ImportMetrics unregistered() {
        return new ImportMetrics(new SimpleMeterRegistry());
    }

    /** A job entered {@code to} (any {@code ImportJob.Status} name); the creation of a job is a transition to OPEN. */
    public void jobTransition(Enum<?> to) {
        safely(() -> Counter.builder(JOB_TRANSITIONS).description("import job status transitions, by target status")
                .tag("to", word(to)).register(registry).increment());
    }

    public void rowApplied() {
        safely(() -> Counter.builder(ROWS_APPLIED).description("import rows minted (verdict recorded)").register(registry).increment());
    }

    public void rowFailed() {
        safely(() -> Counter.builder(ROWS_FAILED).description("import rows that failed on their own terms at apply").register(registry).increment());
    }

    public void leaseLost(Phase phase) {
        safely(() -> Counter.builder(LEASE_LOST).description("worker stopped because its lease was lost or the job was cancelled")
                .tag("phase", word(phase)).register(registry).increment());
    }

    public void paused(PauseReason reason) {
        safely(() -> Counter.builder(PAUSED).description("import job paused by the worker, by reason")
                .tag("reason", word(reason)).register(registry).increment());
    }

    public void tick(TickResult result, long nanos) {
        safely(() -> Timer.builder(TICK_DURATION).description("one import worker tick, claim to release")
                .tag("result", word(result)).register(registry).record(Duration.ofNanos(Math.max(0, nanos))));
    }

    public void bulkRun(BulkKind kind, BulkOutcome outcome) {
        safely(() -> Counter.builder(BULK_RUNS).description("synchronous bulk import runs")
                .tag("kind", word(kind)).tag("outcome", word(outcome)).register(registry).increment());
    }

    public void bulkRows(BulkKind kind, BulkRow outcome, long count) {
        if (count <= 0) {
            return;
        }
        safely(() -> Counter.builder(BULK_ROWS).description("rows of synchronous bulk import runs, by outcome")
                .tag("kind", word(kind)).tag("outcome", word(outcome)).register(registry).increment(count));
    }

    /** The run outcome for a finished (not rejected) synchronous run. */
    public static BulkOutcome outcomeOf(boolean dryRun, boolean stopped, int applied, int failed) {
        if (dryRun) return BulkOutcome.DRY_RUN;
        if (stopped) return BulkOutcome.STOPPED;
        if (failed > 0) return applied > 0 ? BulkOutcome.PARTIAL : BulkOutcome.FAILED;
        return applied > 0 ? BulkOutcome.APPLIED : BulkOutcome.UNCHANGED;
    }

    private static String word(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            log.warn("import metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
