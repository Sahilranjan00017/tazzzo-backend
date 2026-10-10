package com.tazzzo.commerce.read;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionMetricsTest {

    @Test
    void closed_vocabulary_lower_case_words_and_no_overlap_with_the_freshness_family() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProjectionMetrics m = new ProjectionMetrics(registry);
        for (ProjectionMetrics.Conflict c : ProjectionMetrics.Conflict.values()) m.conflict(c);
        for (ProjectionMetrics.Failure f : ProjectionMetrics.Failure.values()) m.failure(f);
        assertThat(registry.find(ProjectionMetrics.CONFLICTS).counters()).hasSize(4);
        assertThat(registry.find(ProjectionMetrics.FAILURES).counters()).hasSize(2);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getName()).as("not a second copy of tazzzo.commerce.freshness.*").doesNotStartWith("tazzzo.commerce.freshness");
            for (Tag t : meter.getId().getTags()) {
                assertThat(ProjectionMetrics.ALLOWED_TAG_KEYS).contains(t.getKey());
                assertThat(t.getValue()).matches("[a-z_]{1,20}");
            }
        }
    }

    @Test
    void a_misbehaving_registry_never_throws_into_the_rebuild() {
        ProjectionMetrics m = new ProjectionMetrics(org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class));
        m.conflict(ProjectionMetrics.Conflict.STALE_CAS);
        m.failure(ProjectionMetrics.Failure.ERROR);
    }
}
