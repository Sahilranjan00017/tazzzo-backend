package com.tazzzo.catalog;

import com.tazzzo.catalog.repo.FreshnessObservability;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10C §8 — the projection freshness loop's bounded operational signals: pure unit tests against
 * a real in-memory {@link SimpleMeterRegistry}, no Mongo/Spring context needed. Proves every meter
 * this class creates uses ONLY the closed tag vocabulary (never a SKU, request id, or location).
 */
class FreshnessObservabilityTest {

    @Test void rebuild_requested_increments_a_bare_counter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.rebuildRequested();
        obs.rebuildRequested();
        assertThat(registry.find(FreshnessObservability.REBUILD_REQUESTED).counter().count()).isEqualTo(2.0);
    }

    @Test void rebuild_attempted_increments_a_bare_counter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.rebuildAttempted();
        assertThat(registry.find(FreshnessObservability.REBUILD_ATTEMPTED).counter().count()).isEqualTo(1.0);
    }

    /**
     * PR-10C final review #3 — {@code rebuildResult} takes the typed enum, not a {@code String}:
     * only these five values can ever exist as a compiled reference, so an arbitrary tag (a SKU, an
     * exception message, anything else) is a COMPILE ERROR, not a runtime risk.
     */
    @Test void only_the_five_allowed_rebuild_results_can_be_constructed() {
        assertThat(FreshnessObservability.RebuildResult.values()).containsExactlyInAnyOrder(
                FreshnessObservability.RebuildResult.CREATED, FreshnessObservability.RebuildResult.UPDATED,
                FreshnessObservability.RebuildResult.NOOP, FreshnessObservability.RebuildResult.REMOVED,
                FreshnessObservability.RebuildResult.MISSING);
    }

    @Test void rebuild_result_is_tagged_by_the_bounded_result_value() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.rebuildResult(FreshnessObservability.RebuildResult.CREATED);
        obs.rebuildResult(FreshnessObservability.RebuildResult.NOOP);
        obs.rebuildResult(FreshnessObservability.RebuildResult.NOOP);
        assertThat(registry.find(FreshnessObservability.REBUILD_RESULT).tags("result", "created")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(FreshnessObservability.REBUILD_RESULT).tags("result", "noop")
                .counter().count()).isEqualTo(2.0);
    }

    @Test void rebuild_failure_increments_a_bare_counter_no_exception_tag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.rebuildFailure();
        var meter = registry.find(FreshnessObservability.REBUILD_FAILURE).meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags()).as("no tags at all -- never an exception type/message").isEmpty();
    }

    @Test void rebuild_completion_distinguishes_cleared_from_superseded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.rebuildCompletion(FreshnessObservability.Completion.CLEARED);
        obs.rebuildCompletion(FreshnessObservability.Completion.SUPERSEDED);
        obs.rebuildCompletion(FreshnessObservability.Completion.SUPERSEDED);
        assertThat(registry.find(FreshnessObservability.REBUILD_COMPLETION).tags("completion", "cleared")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(FreshnessObservability.REBUILD_COMPLETION).tags("completion", "superseded")
                .counter().count()).isEqualTo(2.0);
    }

    @Test void reconcile_enqueued_is_skipped_when_zero_but_recorded_when_positive() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.reconcileEnqueued(FreshnessObservability.ReconcilePass.DRIFT, 0);
        assertThat(registry.find(FreshnessObservability.RECONCILE_ENQUEUED).counter()).isNull();

        obs.reconcileEnqueued(FreshnessObservability.ReconcilePass.DRIFT, 7);
        assertThat(registry.find(FreshnessObservability.RECONCILE_ENQUEUED).tags("pass", "drift")
                .counter().count()).isEqualTo(7.0);
    }

    @Test void reconcile_pass_is_visible_even_when_nothing_was_enqueued() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.reconcilePass(FreshnessObservability.ReconcilePass.ORPHAN);
        assertThat(registry.find(FreshnessObservability.RECONCILE_PASS).tags("pass", "orphan")
                .counter().count()).isEqualTo(1.0);
    }

    @Test void queue_lag_records_a_duration() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.queueLag(Duration.ofSeconds(3));
        assertThat(registry.find(FreshnessObservability.QUEUE_LAG).timer().count()).isEqualTo(1);
    }

    @Test void every_meter_uses_only_the_bounded_tag_vocabulary_no_sku_no_location() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FreshnessObservability obs = new FreshnessObservability(registry);
        obs.rebuildRequested();
        obs.rebuildAttempted();
        obs.rebuildResult(FreshnessObservability.RebuildResult.UPDATED);
        obs.rebuildFailure();
        obs.rebuildCompletion(FreshnessObservability.Completion.CLEARED);
        obs.reconcileEnqueued(FreshnessObservability.ReconcilePass.DRIFT, 3);
        obs.reconcilePass(FreshnessObservability.ReconcilePass.DRIFT);
        obs.queueLag(Duration.ofMillis(500));

        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("tazzzo.commerce.freshness.")) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertThat(FreshnessObservability.ALLOWED_TAG_KEYS).as(meter.getId().getName())
                        .contains(tag.getKey());
                assertThat(tag.getValue()).as(meter.getId().getName() + " tag " + tag.getKey())
                        .doesNotContain("TZP-").doesNotContain("TZS-").doesNotContain("TZC-")
                        .doesNotContain("TZV-").doesNotContain("req_");
            }
        }
    }
}
