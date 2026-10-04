package com.tazzzo.catalog.datastore;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * The application's only {@code TaskScheduler}: every {@code @Scheduled} method, present and future, is registered
 * through it, so none can act before {@link DatastoreReadiness} says the datastore is verified and startup is complete
 * (M1). A tick that arrives while the gate is closed returns immediately without touching anything; the next tick
 * (fixed delay / cron) is scheduled as usual, so a worker simply starts one period after the gate opens. A one-shot
 * task that fires while the gate is closed is skipped and is not rescheduled (fail closed).
 *
 * <p>All scheduling entry points of {@link ThreadPoolTaskScheduler} are overridden; a test pins that none is missed.
 */
public class GatedTaskScheduler extends ThreadPoolTaskScheduler {

    private final transient DatastoreReadiness readiness;

    public GatedTaskScheduler(DatastoreReadiness readiness) {
        this.readiness = readiness;
    }

    private Runnable gated(Runnable task) {
        return () -> {
            if (readiness.workersPermitted()) {
                task.run();
            }
        };
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return super.schedule(gated(task), trigger);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return super.schedule(gated(task), startTime);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return super.scheduleAtFixedRate(gated(task), startTime, period);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return super.scheduleAtFixedRate(gated(task), period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return super.scheduleWithFixedDelay(gated(task), startTime, delay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return super.scheduleWithFixedDelay(gated(task), delay);
    }
}
