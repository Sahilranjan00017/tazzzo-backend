package com.tazzzo.catalog.datastore;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronTrigger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No scheduled business worker may act until the datastore is verified and startup has finished (DB-4 hardening, M1).
 * Pure tests of the gate, of the scheduler that enforces it, and of the source tree so a future {@code @Scheduled}
 * method (or a second scheduler) cannot bypass it unnoticed.
 */
class ScheduledWorkerGateTest {

    // ---- the readiness state machine ----

    @Test
    void the_gate_is_closed_until_verified_and_opened() {
        DatastoreReadiness r = new DatastoreReadiness();
        assertThat(r.workersPermitted()).isFalse();
        r.openWorkers();
        assertThat(r.workersPermitted()).as("opening without verification does nothing").isFalse();
        r.markVerified();
        assertThat(r.workersPermitted()).as("verified is not yet open: startup has not finished").isFalse();
        r.openWorkers();
        assertThat(r.workersPermitted()).isTrue();
        assertThat(r.state()).isEqualTo(DatastoreReadiness.State.OPEN);
    }

    @Test
    void refusal_is_terminal_and_wins_over_everything() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markRefused();
        r.markVerified();
        r.openWorkers();
        r.markJob();
        assertThat(r.workersPermitted()).isFalse();
        assertThat(r.state()).isEqualTo(DatastoreReadiness.State.REFUSED);
    }

    @Test
    void a_migration_job_never_opens_the_gate_even_if_it_was_verified() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markJob();
        r.markVerified();
        r.openWorkers();
        assertThat(r.workersPermitted()).isFalse();
        assertThat(r.state()).isEqualTo(DatastoreReadiness.State.JOB);
    }

    @Test
    void a_verified_process_that_later_turns_out_to_be_refused_is_closed_again() {
        DatastoreReadiness r = new DatastoreReadiness();
        r.markVerified();
        r.openWorkers();
        r.markRefused();
        assertThat(r.workersPermitted()).isFalse();
    }

    // ---- the gated scheduler ----

    private static GatedTaskScheduler scheduler(DatastoreReadiness readiness) {
        GatedTaskScheduler s = new GatedTaskScheduler(readiness);
        s.setPoolSize(2);
        s.initialize();
        return s;
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
    }

    @Test
    void a_fixed_delay_task_does_nothing_while_the_gate_is_closed_and_runs_after_it_opens() throws Exception {
        DatastoreReadiness readiness = new DatastoreReadiness();
        GatedTaskScheduler s = scheduler(readiness);
        try {
            AtomicInteger runs = new AtomicInteger();
            ScheduledFuture<?> f = s.scheduleWithFixedDelay(runs::incrementAndGet, Duration.ofMillis(10));
            Thread.sleep(300);
            assertThat(runs.get()).as("ticks arrived (the pool is alive) but the gate was closed").isZero();
            readiness.markVerified();
            Thread.sleep(150);
            assertThat(runs.get()).as("verified but startup unfinished: still closed").isZero();
            readiness.openWorkers();
            waitFor(() -> runs.get() > 0);
            assertThat(runs.get()).isPositive();
            f.cancel(true);
        } finally {
            s.shutdown();
        }
    }

    @Test
    void every_scheduling_entry_point_is_gated() throws Exception {
        DatastoreReadiness readiness = new DatastoreReadiness();
        GatedTaskScheduler s = scheduler(readiness);
        try {
            AtomicInteger runs = new AtomicInteger();
            Runnable r = runs::incrementAndGet;
            List<ScheduledFuture<?>> fs = new ArrayList<>();
            fs.add(s.schedule(r, Instant.now().plusMillis(20)));
            fs.add(s.schedule(r, new CronTrigger("* * * * * *")));
            fs.add(s.scheduleAtFixedRate(r, Instant.now().plusMillis(20), Duration.ofMillis(20)));
            fs.add(s.scheduleAtFixedRate(r, Duration.ofMillis(20)));
            fs.add(s.scheduleWithFixedDelay(r, Instant.now().plusMillis(20), Duration.ofMillis(20)));
            fs.add(s.scheduleWithFixedDelay(r, Duration.ofMillis(20)));
            Thread.sleep(1500);
            assertThat(runs.get()).as("six registrations, gate closed: nothing ran").isZero();
            fs.forEach(f -> f.cancel(true));
        } finally {
            s.shutdown();
        }
    }

    @Test
    void the_gated_scheduler_overrides_every_scheduling_method_of_its_parent() {
        List<String> missing = new ArrayList<>();
        for (Method m : org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class.getMethods()) {
            if (ScheduledFuture.class.isAssignableFrom(m.getReturnType()) && m.getName().startsWith("schedule")
                    && !m.isAnnotationPresent(Deprecated.class)) {
                try {
                    Method own = GatedTaskScheduler.class.getMethod(m.getName(), m.getParameterTypes());
                    if (own.getDeclaringClass() != GatedTaskScheduler.class) {
                        missing.add(m.toString());
                    }
                } catch (NoSuchMethodException e) {
                    missing.add(m.toString());
                }
            }
        }
        assertThat(missing).as("a scheduling entry point the gate does not wrap would bypass it").isEmpty();
    }

    // ---- the source tree: every @Scheduled worker is accounted for and there is only one scheduler ----

    static final Path MAIN = Path.of("src/main/java");

    private static String code(Path f) throws IOException {
        return Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void the_nine_scheduled_workers_are_exactly_the_inventoried_ones_and_all_run_through_the_gated_scheduler() throws IOException {
        TreeMap<String, Integer> found = new TreeMap<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                String c = code(f);
                int n = c.split("@Scheduled\\(", -1).length - 1;
                if (n > 0) {
                    found.put(f.getFileName().toString(), n);
                }
            }
        }
        assertThat(found).as("a new @Scheduled worker: it is gated automatically by GatedTaskScheduler, but add it here, review that "
                        + "it is idempotent, and document it in DATABASE_STAGING_RUNBOOK.md")
                .containsExactlyEntriesOf(new TreeMap<>(java.util.Map.of()) {{
                    put("CatalogSchedulers.java", 5);
                    put("CommerceProjectionScheduler.java", 2);
                    put("ImportJobScheduler.java", 1);
                    put("InventoryReservationScheduler.java", 1);
                    put("NotificationConfig.java", 1);
                }});
    }

    @Test
    void there_is_no_second_scheduler_and_no_other_way_to_run_timed_background_work() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                String path = f.toString().replace('\\', '/');
                String c = code(f);
                if (path.endsWith("catalog/migration/MigrationRunner.java")) {
                    continue; // the lock-lease heartbeat of a running migration: it renews a lease, it is not business work
                }
                boolean inDatastore = path.contains("com/tazzzo/catalog/datastore/");
                for (String banned : List.of("ScheduledExecutorService", "Executors.newScheduled", "new Timer(", "TimerTask",
                        "SchedulingConfigurer", "TaskScheduler", "@EnableAsync", "new Thread(")) {
                    if (c.contains(banned) && !(inDatastore && (banned.equals("TaskScheduler")))) {
                        offenders.add(path + " -> " + banned);
                    }
                }
                if (c.contains("@EnableScheduling") && !path.endsWith("CatalogApplication.java")) {
                    offenders.add(path + " -> @EnableScheduling");
                }
            }
        }
        assertThat(offenders).as("all timed work must go through the gated scheduler").isEmpty();
    }
}
