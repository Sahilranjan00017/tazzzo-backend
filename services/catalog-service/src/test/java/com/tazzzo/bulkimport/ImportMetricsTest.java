package com.tazzzo.bulkimport;

import com.tazzzo.bulkimport.jobs.ImportJob;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The import meters: closed tag vocabulary, outcome mapping and fail-safe recording. */
class ImportMetricsTest {

    @Test
    void every_meter_uses_only_the_closed_tag_keys_and_lower_case_word_values() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ImportMetrics m = new ImportMetrics(registry);
        for (ImportJob.Status s : ImportJob.Status.values()) m.jobTransition(s);
        m.rowApplied();
        m.rowFailed();
        for (ImportMetrics.Phase p : ImportMetrics.Phase.values()) m.leaseLost(p);
        for (ImportMetrics.PauseReason r : ImportMetrics.PauseReason.values()) m.paused(r);
        for (ImportMetrics.TickResult r : ImportMetrics.TickResult.values()) m.tick(r, 1_000_000);
        for (ImportMetrics.BulkKind k : ImportMetrics.BulkKind.values()) {
            for (ImportMetrics.BulkOutcome o : ImportMetrics.BulkOutcome.values()) m.bulkRun(k, o);
            for (ImportMetrics.BulkRow o : ImportMetrics.BulkRow.values()) m.bulkRows(k, o, 3);
        }
        assertThat(registry.getMeters()).isNotEmpty();
        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(ImportMetrics.ALLOWED_TAG_KEYS).as(meter.getId().getName()).contains(tag.getKey());
                assertThat(tag.getValue()).as(meter.getId().getName() + " " + tag.getKey()).matches("[a-z_]{1,20}");
            }
        }
        assertThat(registry.find(ImportMetrics.JOB_TRANSITIONS).counters()).hasSize(ImportJob.Status.values().length);
        assertThat(registry.find(ImportMetrics.BULK_RUNS).counters())
                .hasSize(ImportMetrics.BulkKind.values().length * ImportMetrics.BulkOutcome.values().length);
    }

    @Test
    void an_unknown_or_hostile_kind_collapses_to_other() {
        assertThat(ImportMetrics.BulkKind.of("prices")).isEqualTo(ImportMetrics.BulkKind.PRICES);
        assertThat(ImportMetrics.BulkKind.of("products")).isEqualTo(ImportMetrics.BulkKind.PRODUCTS);
        assertThat(ImportMetrics.BulkKind.of("inventory")).isEqualTo(ImportMetrics.BulkKind.INVENTORY);
        for (String hostile : new String[]{null, "", "TZP-123", "../etc", "prices; drop", "IMP-5f1e"}) {
            assertThat(ImportMetrics.BulkKind.of(hostile)).as(String.valueOf(hostile)).isEqualTo(ImportMetrics.BulkKind.OTHER);
        }
    }

    @Test
    void run_outcome_mapping_is_total_and_ordered_by_severity() {
        assertThat(ImportMetrics.outcomeOf(true, false, 0, 0)).isEqualTo(ImportMetrics.BulkOutcome.DRY_RUN);
        assertThat(ImportMetrics.outcomeOf(false, true, 2, 1)).as("a datastore stop wins").isEqualTo(ImportMetrics.BulkOutcome.STOPPED);
        assertThat(ImportMetrics.outcomeOf(false, false, 2, 1)).isEqualTo(ImportMetrics.BulkOutcome.PARTIAL);
        assertThat(ImportMetrics.outcomeOf(false, false, 0, 3)).isEqualTo(ImportMetrics.BulkOutcome.FAILED);
        assertThat(ImportMetrics.outcomeOf(false, false, 4, 0)).isEqualTo(ImportMetrics.BulkOutcome.APPLIED);
        assertThat(ImportMetrics.outcomeOf(false, false, 0, 0)).isEqualTo(ImportMetrics.BulkOutcome.UNCHANGED);
    }

    @Test
    void zero_row_counts_create_no_meter_and_a_closed_registry_never_throws_into_the_import() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ImportMetrics m = new ImportMetrics(registry);
        m.bulkRows(ImportMetrics.BulkKind.PRICES, ImportMetrics.BulkRow.APPLIED, 0);
        assertThat(registry.find(ImportMetrics.BULK_ROWS).counters()).isEmpty();
        ImportMetrics broken = new ImportMetrics(org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class));
        broken.rowApplied();                                       // a registry that misbehaves (returns no meter)
        broken.tick(ImportMetrics.TickResult.IDLE, -5);
        broken.jobTransition(ImportJob.Status.OPEN);
        broken.bulkRun(ImportMetrics.BulkKind.OTHER, ImportMetrics.BulkOutcome.REJECTED);
        ImportMetrics.unregistered().rowFailed();
    }
}
