package com.tazzzo.catalog.repo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * PR-10C — bounded operational signals for the projection freshness loop (PR-10A's
 * {@code ProjectionRebuildQueue}/{@code ProjectionRebuildWorker}/{@code ProjectionReconciler}).
 * Lives in {@code catalog.repo} (a neutral leaf both domains AND {@code commerce.read} may depend
 * on — {@code ProjectionRebuildQueue} already sits here for exactly this reason) so the SAME
 * observability seam can be called from a domain write hook (enqueue) and from the
 * {@code commerce.read} worker/reconciler (drain/reconcile) without violating the
 * {@code domains ↛ commerce.read} / {@code commerce.api ↛ domain internals} ArchUnit rules.
 *
 * <p><b>No SKU, no request, no location tag — ever.</b> Every tag value is from a small closed
 * enum (a rebuild result, a completion kind, a reconcile pass) or absent entirely. Optional by
 * design: existing constructors that predate this class keep working with a {@code null}
 * observability (no-op), so nothing is forced to wire it.
 */
@Component
public class FreshnessObservability {

    private static final Logger log = LoggerFactory.getLogger(FreshnessObservability.class);

    public static final String REBUILD_REQUESTED = "tazzzo.commerce.freshness.rebuild.requested";
    public static final String REBUILD_ATTEMPTED = "tazzzo.commerce.freshness.rebuild.attempted";
    public static final String REBUILD_RESULT = "tazzzo.commerce.freshness.rebuild.result";
    public static final String REBUILD_FAILURE = "tazzzo.commerce.freshness.rebuild.failure";
    public static final String REBUILD_COMPLETION = "tazzzo.commerce.freshness.rebuild.completion";
    public static final String RECONCILE_ENQUEUED = "tazzzo.commerce.freshness.reconcile.enqueued";
    public static final String RECONCILE_PASS = "tazzzo.commerce.freshness.reconcile.pass";
    public static final String QUEUE_LAG = "tazzzo.commerce.freshness.queue.lag";

    /** The complete tag vocabulary for this metric family. */
    public static final Set<String> ALLOWED_TAG_KEYS = Set.of("result", "completion", "pass");

    public enum Completion { CLEARED, SUPERSEDED }

    public enum ReconcilePass { DRIFT, ORPHAN }

    /**
     * PR-10C final review #3 — the bounded derivation-result vocabulary, typed here (not a
     * caller-supplied {@code String}) so the cardinality contract is enforced by the compiler, not
     * just documentation. Mirrors {@code commerce.read.RebuildOutcome} one-for-one; this class
     * cannot import that type (ArchUnit: {@code domains ↛ commerce.read}), so the CALLER
     * ({@link com.tazzzo.commerce.read.ProjectionRebuildWorker}, which may depend on catalog.repo)
     * maps its own enum to this one explicitly.
     */
    public enum RebuildResult { CREATED, UPDATED, NOOP, REMOVED, MISSING }

    private final MeterRegistry registry;

    public FreshnessObservability(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /** One rebuild request was enqueued (a source hook fired, or a reconcile pass re-armed a row). */
    public void rebuildRequested() {
        safely(() -> Counter.builder(REBUILD_REQUESTED).register(registry).increment());
    }

    /** The worker claimed one item and is about to derive it. */
    public void rebuildAttempted() {
        safely(() -> Counter.builder(REBUILD_ATTEMPTED).register(registry).increment());
    }

    /** The bounded derivation result. See {@link RebuildResult}. */
    public void rebuildResult(RebuildResult result) {
        safely(() -> Counter.builder(REBUILD_RESULT)
                .tag("result", result.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    /** The derivation itself threw; the item stays leased for retry (no exception detail tagged). */
    public void rebuildFailure() {
        safely(() -> Counter.builder(REBUILD_FAILURE).register(registry).increment());
    }

    /** Whether the claimed row was actually cleared, or superseded by a newer generation. */
    public void rebuildCompletion(Completion completion) {
        safely(() -> Counter.builder(REBUILD_COMPLETION)
                .tag("completion", completion.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    /** How many rows a reconcile pass enqueued (0 is a legitimate, common result). */
    public void reconcileEnqueued(ReconcilePass pass, int count) {
        if (count <= 0) {
            return;
        }
        safely(() -> Counter.builder(RECONCILE_ENQUEUED)
                .tag("pass", pass.name().toLowerCase(Locale.ROOT))
                .register(registry).increment(count));
    }

    /** One reconcile pass ran (visible even when it enqueued nothing). */
    public void reconcilePass(ReconcilePass pass) {
        safely(() -> Counter.builder(RECONCILE_PASS)
                .tag("pass", pass.name().toLowerCase(Locale.ROOT))
                .register(registry).increment());
    }

    /**
     * Time between a work item's {@code requested_at} and the moment THIS drain claimed it — a
     * cheap, already-in-hand measurement (no extra query): queue age/lag without a full scan.
     */
    public void queueLag(Duration lag) {
        safely(() -> Timer.builder(QUEUE_LAG).register(registry).record(lag));
    }

    private static void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            // PR-10C final review #4: class only, never the message (which could embed a raw tag
            // value or registry-internal detail we don't control).
            log.warn("freshness metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
