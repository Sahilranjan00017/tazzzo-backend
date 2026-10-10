package com.tazzzo.common.metrics;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The snapshot discipline every database-backed gauge relies on, with a fake clock and no database. */
class SnapshotCacheTest {

    static final class MovingClock extends Clock {
        volatile Instant now = Instant.parse("2026-06-01T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void reloads_at_most_once_per_ttl_however_often_it_is_read() {
        MovingClock clock = new MovingClock();
        AtomicInteger loads = new AtomicInteger();
        SnapshotCache<Integer> cache = new SnapshotCache<>(-1, clock, Duration.ofSeconds(15), now -> loads.incrementAndGet());
        for (int i = 0; i < 50; i++) assertThat(cache.get()).isEqualTo(1);
        clock.now = clock.now.plusSeconds(14);
        assertThat(cache.get()).isEqualTo(1);
        assertThat(loads.get()).isEqualTo(1);
        clock.now = clock.now.plusSeconds(1);
        assertThat(cache.get()).isEqualTo(2);
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void a_failing_load_serves_the_initial_value_first_then_keeps_the_last_good_snapshot_and_retries_once_per_ttl() {
        MovingClock clock = new MovingClock();
        AtomicInteger loads = new AtomicInteger();
        boolean[] fail = {true};
        SnapshotCache<Double> cache = new SnapshotCache<>(Double.NaN, clock, Duration.ofSeconds(10), now -> {
            loads.incrementAndGet();
            if (fail[0]) throw new IllegalStateException("database down");
            return 7.0;
        });
        assertThat(cache.get()).isNaN();
        assertThat(cache.get()).as("not retried inside the ttl").isNaN();
        assertThat(loads.get()).isEqualTo(1);

        fail[0] = false;
        clock.now = clock.now.plusSeconds(10);
        assertThat(cache.get()).isEqualTo(7.0);

        fail[0] = true;
        clock.now = clock.now.plusSeconds(10);
        assertThat(cache.get()).as("the last good snapshot survives a failed refresh").isEqualTo(7.0);
        int afterFail = loads.get();
        assertThat(cache.get()).isEqualTo(7.0);
        assertThat(loads.get()).as("a failed refresh still advances the load time").isEqualTo(afterFail);
    }

    @Test
    void a_clock_that_moves_backwards_reloads() {
        MovingClock clock = new MovingClock();
        AtomicInteger loads = new AtomicInteger();
        SnapshotCache<Integer> cache = new SnapshotCache<>(0, clock, Duration.ofSeconds(60), now -> loads.incrementAndGet());
        cache.get();
        clock.now = clock.now.minusSeconds(5);
        cache.get();
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void concurrent_readers_during_a_slow_refresh_keep_the_previous_snapshot_and_trigger_exactly_one_load() throws Exception {
        MovingClock clock = new MovingClock();
        CountDownLatch inLoad = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        SnapshotCache<Integer> cache = new SnapshotCache<>(0, clock, Duration.ofSeconds(5), now -> {
            int n = loads.incrementAndGet();
            if (n == 2) {
                inLoad.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return n;
        });
        assertThat(cache.get()).isEqualTo(1);
        clock.now = clock.now.plusSeconds(5);
        Thread refresher = new Thread(cache::get);
        refresher.start();
        assertThat(inLoad.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 20; i++) assertThat(cache.get()).as("served without waiting on the refresh").isEqualTo(1);
        assertThat(loads.get()).isEqualTo(2);
        release.countDown();
        refresher.join(5000);
        assertThat(cache.get()).isEqualTo(2);
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void the_refresh_interval_is_validated_and_names_the_property() {
        assertThat(SnapshotCache.refreshInterval(15, "p")).isEqualTo(Duration.ofSeconds(15));
        assertThatThrownBy(() -> SnapshotCache.refreshInterval(0, "tazzzo.x")).hasMessage("tazzzo.x must be 1..3600");
        assertThatThrownBy(() -> SnapshotCache.refreshInterval(3601, "tazzzo.x")).isInstanceOf(IllegalStateException.class);
    }
}
