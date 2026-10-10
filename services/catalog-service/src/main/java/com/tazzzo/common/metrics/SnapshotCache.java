package com.tazzzo.common.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The snapshot discipline for database-backed gauges (the same one {@code NotificationMetrics} follows): the value is
 * reloaded at most once per {@code ttl} however often it is scraped, the reload runs outside any lock and exactly one
 * caller performs it (concurrent scrapes keep reading the previous snapshot: single flight), and a failing reload keeps the
 * last good snapshot (the initial one before the first success) while still advancing the load time, so a failing
 * database is retried once per ttl, not once per scrape. A clock that moves backwards also triggers a reload.
 *
 * @param <T> an immutable snapshot
 */
public final class SnapshotCache<T> {

    private record State<T>(T snapshot, Instant loadedAt) { }

    private final Clock clock;
    private final Duration ttl;
    private final Function<Instant, T> loader;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile State<T> state;

    /**
     * @param initial what a scrape sees until the first successful load (gauges use NaN)
     * @param loader computes a fresh snapshot for "now"; it must bound its own queries (cap and max time)
     */
    public SnapshotCache(T initial, Clock clock, Duration ttl, Function<Instant, T> loader) {
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        this.loader = Objects.requireNonNull(loader);
        this.state = new State<>(initial, null);
    }

    public T get() {
        State<T> s = state;
        Instant now = clock.instant();
        boolean stale = s.loadedAt() == null || Duration.between(s.loadedAt(), now).compareTo(ttl) >= 0
                || now.isBefore(s.loadedAt());
        if (stale && refreshing.compareAndSet(false, true)) {
            try {
                T next = s.snapshot();
                try {
                    next = loader.apply(now);
                } catch (RuntimeException e) {
                    // keep the last good snapshot; loadedAt still advances so a failing database is retried once per ttl
                }
                state = new State<>(next, now);
            } finally {
                refreshing.set(false);
            }
        }
        return state.snapshot();
    }

    /** Validates the shared refresh-interval property (1..3600 seconds), naming it on failure. */
    public static Duration refreshInterval(long seconds, String property) {
        if (seconds < 1 || seconds > 3600) {
            throw new IllegalStateException(property + " must be 1..3600");
        }
        return Duration.ofSeconds(seconds);
    }
}
