package com.tazzzo.catalog.datastore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.migration.MigrationExitHandler;
import com.tazzzo.catalog.ops.CatalogSchedulers;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.MongoDBContainer;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-4 hardening M1, against the real application and a real MongoDB 7 replica set, with the scheduler ENABLED and every
 * period tiny (so that, without the gate, every worker would act within milliseconds of context refresh):
 *
 * <ul>
 *   <li>a startup the datastore verifier REFUSES performs no scheduled write: the price ledger is untouched, no rollup or
 *       purge ran, nothing was created;</li>
 *   <li>the same when the refusal comes later, from the migration startup runner (schema not at the required state);</li>
 *   <li>a migration DRY_RUN job is read-only even though the scheduler flag is on: no business, schema, history or lock write;</li>
 *   <li>a healthy serving start opens the gate, so the gate is not simply stuck shut.</li>
 * </ul>
 * The price ledger here is R1's exact scenario ({@code RollupService.purge} deletes {@code rolled} {@code price_events}).
 */
class StartupSchedulerGateIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start();
    }

    static final AtomicInteger PROBE_RUNS = new AtomicInteger();
    static final List<Integer> EXIT_CODES = new CopyOnWriteArrayList<>();

    /** A stand-in business worker: any @Scheduled method must be held back by the same gate as the real eight. */
    @Component
    static class ProbeWorker {
        @Scheduled(fixedDelay = 10)
        void tick() {
            PROBE_RUNS.incrementAndGet();
        }
    }

    @TestConfiguration
    static class RecordingExit {
        @Bean
        @Primary
        MigrationExitHandler recordingExit() {
            return EXIT_CODES::add;
        }
    }

    static MongoClient client = MongoClients.create(MONGO.getReplicaSetUrl());

    @AfterAll
    static void close() {
        client.close();
    }

    @BeforeEach
    void reset() {
        PROBE_RUNS.set(0);
        EXIT_CODES.clear();
    }

    private static MongoDatabase seeded(String name) {
        MongoDatabase d = client.getDatabase(name);
        d.drop();
        Date old = new Date(System.currentTimeMillis() - 86_400_000L);
        d.getCollection("price_events").insertMany(List.of(
                // a durable paise-shape ledger row and a legacy-shaped row: both are destroyed by the rollup + purge path
                new Document("_id", "durable").append("product_id", "TZP-1").append("seller", "S").append("price", 19900).append("ts", old),
                new Document("_id", "legacy").append("product_id", "TZP-2").append("seller", "S").append("price", 120).append("ts", old)));
        return d;
    }

    private static void assertLedgerUntouched(MongoDatabase d) {
        assertThat(d.getCollection("price_events").countDocuments()).as("both ledger rows survive").isEqualTo(2);
        assertThat(d.getCollection("price_events").countDocuments(new Document("rolled", true))).as("nothing was marked rolled").isZero();
        assertThat(new TreeSet<>(d.listCollectionNames().into(new java.util.ArrayList<>())))
                .as("no rollup/aggregate, event, history, lock or schema collection was created")
                .isEqualTo(Set.of("price_events"));
    }

    private static String[] args(String db, String... extra) {
        List<String> a = new java.util.ArrayList<>(List.of(
                "--spring.data.mongodb.uri=" + MONGO.getReplicaSetUrl(),
                "--spring.data.mongodb.database=" + db,
                "--tazzzo.schema.load-taxonomy-seed=false",
                "--tazzzo.consumer-rate-limit.mode=DISABLED",
                "--tazzzo.scheduler.enabled=true",
                "--tazzzo.scheduler.merge-finalizer-ms=10", "--tazzzo.scheduler.taint-ms=10", "--tazzzo.scheduler.stamp-ms=10",
                "--tazzzo.scheduler.ck-backfill-ms=10", "--tazzzo.scheduler.rollup-ms=10", "--tazzzo.scheduler.rollup-lag-seconds=0"));
        a.addAll(List.of(extra));
        return a.toArray(String[]::new);
    }

    private static SpringApplicationBuilder app() {
        return new SpringApplicationBuilder(CatalogApplication.class, ProbeWorker.class, RecordingExit.class).web(WebApplicationType.NONE);
    }

    @Test
    void a_startup_the_datastore_verifier_refuses_runs_no_scheduled_worker() throws Exception {
        MongoDatabase d = seeded("tazzzo_gate_refused");
        // staging + a connection string that violates the contract (no TLS, loopback, no explicit options): refused
        assertThatThrownBy(() -> app().run(args("tazzzo_gate_refused", "--tazzzo.migration.mode=VERIFY",
                "--tazzzo.migration.environment=staging")))
                .isInstanceOf(DatastoreContractException.class).hasMessageContaining("TLS_REQUIRED");
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(PROBE_RUNS.get()).as("the stand-in worker never ran").isZero();
        assertLedgerUntouched(d);
    }

    @Test
    void a_startup_refused_later_by_the_migration_runner_also_runs_no_scheduled_worker() throws Exception {
        MongoDatabase d = seeded("tazzzo_gate_unmigrated");
        // dev + loopback: the datastore verifier is satisfied (advisory), but the schema was never migrated, so VERIFY refuses.
        // The window between the verifier and that refusal is a real database round-trip: workers must not use it.
        assertThatThrownBy(() -> app().run(args("tazzzo_gate_unmigrated", "--tazzzo.migration.mode=VERIFY",
                "--tazzzo.migration.environment=dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database schema verification failed");
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(PROBE_RUNS.get()).isZero();
        assertLedgerUntouched(d);
    }

    @Test
    void a_dry_run_job_is_read_only_even_when_the_scheduler_flag_is_left_on() throws Exception {
        MongoDatabase d = seeded("tazzzo_gate_dryrun");
        try (ConfigurableApplicationContext ctx = app().run(args("tazzzo_gate_dryrun", "--tazzzo.migration.mode=DRY_RUN",
                "--tazzzo.migration.environment=dev", "--tazzzo.migration.exit-after-run=true"))) {
            assertThat(ctx.getBeansOfType(CatalogSchedulers.class)).as("the real scheduler beans ARE present").hasSize(1);
            TimeUnit.MILLISECONDS.sleep(1500); // ~150 periods of every worker
            assertThat(EXIT_CODES).containsExactly(0);
            assertThat(ctx.getBean(DatastoreReadiness.class).state()).isEqualTo(DatastoreReadiness.State.JOB);
            assertThat(PROBE_RUNS.get()).isZero();
            assertLedgerUntouched(d); // no business write, no schema write, no history, no lock, no scheduler write
        }
    }

    @Test
    void a_dry_run_that_does_not_exit_keeps_serving_but_still_runs_no_worker() throws Exception {
        MongoDatabase d = seeded("tazzzo_gate_dryrun_noexit");
        // exit-after-run=false: the job finishes with code 0 and startup CONTINUES normally; the process is still a job
        try (ConfigurableApplicationContext ctx = app().run(args("tazzzo_gate_dryrun_noexit", "--tazzzo.migration.mode=DRY_RUN",
                "--tazzzo.migration.environment=dev", "--tazzzo.migration.exit-after-run=false"))) {
            TimeUnit.MILLISECONDS.sleep(1000);
            assertThat(ctx.getBean(DatastoreReadiness.class).state()).isEqualTo(DatastoreReadiness.State.JOB);
            assertThat(PROBE_RUNS.get()).isZero();
            assertLedgerUntouched(d);
        }
    }

    @Test
    void a_healthy_serving_start_opens_the_gate_and_workers_then_run() throws Exception {
        client.getDatabase("tazzzo_gate_serving").drop();
        try (ConfigurableApplicationContext ctx = app().run(args("tazzzo_gate_serving", "--tazzzo.migration.mode=APPLY_ON_STARTUP",
                "--tazzzo.migration.environment=dev"))) {
            assertThat(ctx.getBean(DatastoreReadiness.class).state()).isEqualTo(DatastoreReadiness.State.OPEN);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (PROBE_RUNS.get() == 0 && System.nanoTime() < end) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertThat(PROBE_RUNS.get()).as("workers run once the gate is open").isPositive();
        }
    }
}
