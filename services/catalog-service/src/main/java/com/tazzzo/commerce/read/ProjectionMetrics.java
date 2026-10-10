package com.tazzzo.commerce.read;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What happens INSIDE one {@link ProductCardProjectionService#rebuildOne} call, which the freshness meters
 * ({@code tazzzo.commerce.freshness.*}: requested, attempted, per-rebuild result, failure, completion, reconcile, queue lag)
 * cannot see: the optimistic-concurrency retries a rebuild needed and why a rebuild gave up. Nothing here repeats a
 * freshness meter, and every tag value is a constant of a closed enum (never a SKU, a request or a message).
 * <ul>
 *   <li>{@code projection_rebuild_conflicts{reason}}: one retry of the observe-everything loop.</li>
 *   <li>{@code projection_rebuild_failures{kind}}: a rebuild that threw: {@code non_converged} (still conflicting after the
 *       attempt limit, or a version overflow) or {@code error} (a source read or write failed).</li>
 * </ul>
 */
public class ProjectionMetrics {

    private static final Logger log = LoggerFactory.getLogger(ProjectionMetrics.class);

    public static final String CONFLICTS = "projection_rebuild_conflicts";
    public static final String FAILURES = "projection_rebuild_failures";
    public static final Set<String> ALLOWED_TAG_KEYS = Set.of("reason", "kind");

    public enum Conflict { DELETE_RACE, WATERMARK_CAS, CREATE_RACE, STALE_CAS }

    public enum Failure { NON_CONVERGED, ERROR }

    private final MeterRegistry registry;

    public ProjectionMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public static ProjectionMetrics unregistered() {
        return new ProjectionMetrics(new SimpleMeterRegistry());
    }

    public void conflict(Conflict reason) {
        count(CONFLICTS, "reason", reason, "rebuild retries caused by a concurrent writer, by cause");
    }

    public void failure(Failure kind) {
        count(FAILURES, "kind", kind, "rebuilds that threw, by kind");
    }

    private void count(String name, String tag, Enum<?> value, String description) {
        try {
            Counter.builder(name).description(description).tag(tag, value.name().toLowerCase(Locale.ROOT))
                    .register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("projection metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
