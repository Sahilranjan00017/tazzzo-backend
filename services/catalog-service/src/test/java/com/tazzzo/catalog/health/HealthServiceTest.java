package com.tazzzo.catalog.health;

import com.tazzzo.catalog.datastore.DatastoreReadiness;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Readiness semantics, deterministic: gate state, required/optional dependencies, bounded probes, caching. */
class HealthServiceTest {

    private static DatastoreReadiness open() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markVerified();
        r.openWorkers();
        return r;
    }

    private static HealthService service(DatastoreReadiness r, BooleanSupplier mongo, BooleanSupplier limiter,
                                         boolean requireMongo, boolean requireLimiter, Clock clock) {
        return new HealthService(r, mongo, limiter, requireMongo, requireLimiter, clock);
    }

    @Test
    void liveness_never_consults_a_dependency() {
        AtomicInteger probes = new AtomicInteger();
        HealthService s = service(new DatastoreReadiness(), () -> { probes.incrementAndGet(); return false; },
                () -> { probes.incrementAndGet(); return false; }, true, true, Clock.systemUTC());
        HealthReport live = s.live();
        assertThat(live.up()).isTrue();
        assertThat(live.datastore()).isEqualTo("STARTING");
        assertThat(probes.get()).isZero();
    }

    @Test
    void readiness_requires_the_serving_gate_to_be_open_and_skips_probes_until_then() {
        AtomicInteger probes = new AtomicInteger();
        BooleanSupplier counting = () -> { probes.incrementAndGet(); return true; };
        for (DatastoreReadiness r : new DatastoreReadiness[]{new DatastoreReadiness(), refused(), job(), verifiedOnly()}) {
            HealthReport rep = service(r, counting, counting, true, true, Clock.systemUTC()).ready();
            assertThat(rep.up()).as(r.state().name()).isFalse();
            assertThat(rep.mongo()).isEqualTo("SKIPPED");
        }
        assertThat(probes.get()).isZero();
        assertThat(service(refused(), counting, counting, true, true, Clock.systemUTC()).ready().datastore()).isEqualTo("REFUSED");
        assertThat(service(job(), counting, counting, true, true, Clock.systemUTC()).ready().datastore()).isEqualTo("JOB");
    }

    @Test
    void mongo_is_required_by_default_and_the_rate_limiter_is_reported_but_optional() {
        assertThat(service(open(), () -> true, () -> true, true, false, Clock.systemUTC()).ready().up()).isTrue();
        HealthReport mongoDown = service(open(), () -> false, () -> true, true, false, Clock.systemUTC()).ready();
        assertThat(mongoDown.up()).isFalse();
        assertThat(mongoDown.mongo()).isEqualTo("DOWN");
        HealthReport limiterDown = service(open(), () -> true, () -> false, true, false, Clock.systemUTC()).ready();
        assertThat(limiterDown.up()).as("optional by default: reported, not failed").isTrue();
        assertThat(limiterDown.rateLimiter()).isEqualTo("DOWN");
        assertThat(service(open(), () -> true, () -> false, true, true, Clock.systemUTC()).ready().up()).as("required when configured").isFalse();
        assertThat(service(open(), () -> false, () -> true, false, false, Clock.systemUTC()).ready().up()).as("mongo made optional").isTrue();
        HealthReport disabled = service(open(), () -> true, null, true, true, Clock.systemUTC()).ready();
        assertThat(disabled.up()).isTrue();
        assertThat(disabled.rateLimiter()).isEqualTo("DISABLED");
    }

    @Test
    void a_throwing_or_hanging_probe_is_down_not_an_error_and_is_bounded() {
        HealthReport throwing = service(open(), () -> { throw new IllegalStateException("boom"); }, () -> true, true, false, Clock.systemUTC()).ready();
        assertThat(throwing.up()).isFalse();
        assertThat(throwing.mongo()).isEqualTo("DOWN");
        long start = System.nanoTime();
        HealthReport hanging = service(open(), () -> { try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return true; },
                () -> true, true, false, Clock.systemUTC()).ready();
        assertThat(hanging.mongo()).isEqualTo("DOWN");
        assertThat((System.nanoTime() - start) / 1_000_000).as("bounded by the probe timeout").isLessThan(HealthService.PROBE_TIMEOUT_MILLIS + 1_500);
    }

    @Test
    void readiness_is_cached_briefly_so_probers_cannot_cause_a_ping_storm() {
        AtomicInteger probes = new AtomicInteger();
        Instant[] now = {Instant.parse("2026-10-05T00:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        HealthService s = service(open(), () -> { probes.incrementAndGet(); return true; }, null, true, false, clock);
        for (int i = 0; i < 20; i++) {
            s.ready();
        }
        assertThat(probes.get()).isEqualTo(1);
        now[0] = now[0].plusMillis(HealthService.CACHE_MILLIS + 1);
        s.ready();
        assertThat(probes.get()).isEqualTo(2);
    }

    @Test
    void the_body_holds_bounded_words_only() {
        HealthReport r = service(open(), () -> true, () -> true, true, false, Clock.systemUTC()).ready();
        assertThat(r.toBody().keySet()).containsExactly("status", "components");
        assertThat(r.toBody().get("status")).isEqualTo("UP");
        assertThat(((java.util.Map<?, ?>) r.toBody().get("components")).values()).allMatch(v -> v.toString().matches("[A-Z]+"));
    }

    private static DatastoreReadiness refused() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markRefused();
        return r;
    }

    private static DatastoreReadiness job() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markJob();
        return r;
    }

    private static DatastoreReadiness verifiedOnly() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markVerified();
        return r;
    }
}
