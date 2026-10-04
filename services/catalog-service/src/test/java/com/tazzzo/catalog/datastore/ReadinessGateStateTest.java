package com.tazzzo.catalog.datastore;

import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterSettings;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.migration.MigrationRunner;
import com.tazzzo.catalog.migration.MigrationStartupRunner;
import com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.tazzzo.catalog.datastore.DatastoreReadiness.State.JOB;
import static com.tazzzo.catalog.datastore.DatastoreReadiness.State.OPEN;
import static com.tazzzo.catalog.datastore.DatastoreReadiness.State.PENDING;
import static com.tazzzo.catalog.datastore.DatastoreReadiness.State.REFUSED;
import static com.tazzzo.catalog.datastore.DatastoreReadiness.State.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DETERMINISTIC gate-state tests (no database, no waiting to see whether a scheduled task happened): the final
 * {@link DatastoreReadiness} state is asserted directly for every migration mode and every way the migration runner can
 * refuse, and a task submitted to the gated scheduler is awaited to completion and shown not to have run.
 */
class ReadinessGateStateTest {

    static final MigrationRunner.RunReport OK = new MigrationRunner.RunReport(MigrationRunner.Outcome.OK, false, "t", List.of(), "m");
    static final MigrationRunner.RunReport FAILED = new MigrationRunner.RunReport(MigrationRunner.Outcome.FAILED, false, "t", List.of(), "m");
    static final MigrationRunner.VerifyReport VERIFY_OK = new MigrationRunner.VerifyReport(true, List.of(), List.of());
    static final MigrationRunner.VerifyReport VERIFY_BAD = new MigrationRunner.VerifyReport(false, List.of("schema stale"), List.of());

    private static final class Fixture {
        final DatastoreReadiness readiness = new DatastoreReadiness();
        final MigrationRunner runner = mock(MigrationRunner.class);
        final MigrationProperties props = new MigrationProperties();
        final List<Integer> exits = new ArrayList<>();
        final MigrationStartupRunner startup;

        Fixture(MigrationMode mode, boolean exitAfterRun) {
            this(mode, exitAfterRun, "mongodb://localhost:27017/tazzzo_test");
        }

        Fixture(MigrationMode mode, boolean exitAfterRun, String uri) {
            props.setMode(mode);
            props.setEnvironment("dev");
            props.setExitAfterRun(exitAfterRun);
            MongoClient client = mock(MongoClient.class);
            ClusterDescription cd = mock(ClusterDescription.class);
            when(client.getClusterDescription()).thenReturn(cd);
            when(cd.getClusterSettings()).thenReturn(ClusterSettings.builder().hosts(List.of(new ServerAddress("localhost", 27017))).build());
            MongoDatabase db = mock(MongoDatabase.class);
            when(db.getName()).thenReturn("tazzzo_test");
            startup = new MigrationStartupRunner(runner, props, client, db, mock(SchemaBootstrap.class), null,
                    mock(DiscriminatingAttributeRegistry.class), false, false, exits::add, readiness, uri);
        }

        void run() {
            startup.run(new DefaultApplicationArguments());
        }
    }

    // ---- migration-runner refusal (verifier succeeded, runner then fails) ----

    @Test
    void a_schema_verification_refusal_leaves_the_gate_not_open() {
        Fixture f = new Fixture(MigrationMode.VERIFY, false);
        f.readiness.markVerified(); // the datastore verifier succeeded
        when(f.runner.verify(any())).thenReturn(VERIFY_BAD);
        assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("verification failed");
        assertThat(f.readiness.state()).isEqualTo(VERIFIED).isNotEqualTo(OPEN);
        assertThat(f.readiness.workersPermitted()).isFalse();
    }

    @Test
    void a_failed_startup_migration_leaves_the_gate_not_open() {
        Fixture f = new Fixture(MigrationMode.APPLY_ON_STARTUP, false);
        f.readiness.markVerified();
        when(f.runner.apply(any(), any(), any())).thenReturn(FAILED);
        assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
        assertThat(f.readiness.state()).isEqualTo(VERIFIED);
        assertThat(f.readiness.workersPermitted()).isFalse();
    }

    @Test
    void the_target_handed_to_the_migration_runner_carries_the_authoritative_local_decision() {
        for (String[] c : new String[][]{{"mongodb://localhost:27017/tazzzo_test", "true"},
                {"mongodb://localhost:27017/tazzzo_test?proxyHost=remote.example.net", "false"},
                {"mongodb://0177.0.0.1:27017/tazzzo_test", "false"}}) {
            Fixture f = new Fixture(MigrationMode.APPLY_ON_STARTUP, false, c[0]);
            f.readiness.markVerified();
            when(f.runner.apply(any(), any(), any())).thenReturn(OK);
            f.run();
            org.mockito.ArgumentCaptor<com.tazzzo.catalog.migration.MigrationTarget> captured =
                    org.mockito.ArgumentCaptor.forClass(com.tazzzo.catalog.migration.MigrationTarget.class);
            org.mockito.Mockito.verify(f.runner).apply(captured.capture(), any(), any());
            assertThat(captured.getValue().local()).as(c[0]).isEqualTo(Boolean.parseBoolean(c[1]));
        }
    }

    // ---- healthy control ----

    @Test
    void a_healthy_serving_startup_opens_the_gate() {
        Fixture f = new Fixture(MigrationMode.VERIFY, false);
        f.readiness.markVerified();
        when(f.runner.verify(any())).thenReturn(VERIFY_OK);
        f.run();
        assertThat(f.readiness.state()).isEqualTo(OPEN);
        assertThat(f.readiness.workersPermitted()).isTrue();
    }

    @Test
    void a_serving_startup_whose_datastore_was_never_verified_does_not_open_the_gate() {
        Fixture f = new Fixture(MigrationMode.VERIFY, false);
        when(f.runner.verify(any())).thenReturn(VERIFY_OK);
        f.run();
        assertThat(f.readiness.state()).isEqualTo(PENDING);
        assertThat(f.readiness.workersPermitted()).isFalse();
    }

    // ---- migration jobs: DRY_RUN and APPLY never serve ----

    @Test
    void dry_run_leaves_the_gate_in_job_state_whether_or_not_the_process_exits() {
        for (boolean exit : new boolean[]{true, false}) {
            Fixture f = new Fixture(MigrationMode.DRY_RUN, exit);
            f.readiness.markVerified();
            when(f.runner.dryRun(any(), any())).thenReturn(new MigrationRunner.RunReport(MigrationRunner.Outcome.OK, true, "t", List.of(), "m"));
            f.run();
            assertThat(f.readiness.state()).as("exit=" + exit).isEqualTo(JOB);
            assertThat(f.readiness.workersPermitted()).isFalse();
        }
    }

    @Test
    void apply_leaves_the_gate_in_job_state_whether_or_not_the_process_exits_and_even_when_it_fails() {
        for (boolean exit : new boolean[]{true, false}) {
            Fixture f = new Fixture(MigrationMode.APPLY, exit);
            f.readiness.markVerified();
            when(f.runner.apply(any(), any(), any())).thenReturn(OK);
            f.run();
            assertThat(f.readiness.state()).as("apply exit=" + exit).isEqualTo(JOB);
            assertThat(f.readiness.workersPermitted()).isFalse();
        }
        Fixture failing = new Fixture(MigrationMode.APPLY, false);
        failing.readiness.markVerified();
        when(failing.runner.apply(any(), any(), any())).thenReturn(FAILED);
        assertThatThrownBy(failing::run).isInstanceOf(IllegalStateException.class);
        assertThat(failing.readiness.state()).isEqualTo(JOB);
        assertThat(failing.readiness.workersPermitted()).isFalse();
    }

    @Test
    void the_datastore_verifier_alone_marks_each_mode_correctly_before_the_runner_even_starts() {
        for (MigrationMode mode : MigrationMode.values()) {
            DatastoreReadiness readiness = new DatastoreReadiness();
            MigrationProperties m = new MigrationProperties();
            m.setMode(mode);
            m.setEnvironment("dev");
            new DatastoreStartupVerifier(null, "tazzzo_test", m, new DatastoreProperties(), "mongodb://localhost:27017/t",
                    SchemaBootstrap.COLLECTIONS, readiness).run(new DefaultApplicationArguments()); // loopback + dev: advisory, no database touched
            boolean job = mode == MigrationMode.DRY_RUN || mode == MigrationMode.APPLY;
            assertThat(readiness.state()).as(mode.name()).isEqualTo(job ? JOB : VERIFIED);
            assertThat(readiness.workersPermitted()).isFalse();
        }
        DatastoreReadiness refused = new DatastoreReadiness();
        MigrationProperties staging = new MigrationProperties();
        staging.setMode(MigrationMode.APPLY);
        staging.setEnvironment("staging");
        assertThatThrownBy(() -> new DatastoreStartupVerifier(null, "tazzzo_staging", staging, new DatastoreProperties(),
                "mongodb://localhost:27017/t", SchemaBootstrap.COLLECTIONS, refused).run(new DefaultApplicationArguments()))
                .isInstanceOf(DatastoreContractException.class);
        assertThat(refused.state()).as("refusal wins over job mode").isEqualTo(REFUSED);
    }

    // ---- scheduled business tasks cannot run unless the gate is OPEN (awaited, not slept) ----

    private static boolean ranThroughGatedScheduler(DatastoreReadiness readiness) throws Exception {
        GatedTaskScheduler scheduler = new GatedTaskScheduler(readiness);
        scheduler.setPoolSize(1);
        scheduler.initialize();
        try {
            AtomicBoolean ran = new AtomicBoolean();
            ScheduledFuture<?> f = scheduler.schedule(() -> ran.set(true), Instant.now());
            f.get(); // completes only after the wrapped task has been executed (or skipped by the gate)
            return ran.get();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void a_scheduled_business_task_runs_only_when_the_gate_is_open() throws Exception {
        DatastoreReadiness r = new DatastoreReadiness();
        assertThat(ranThroughGatedScheduler(r)).as("PENDING").isFalse();
        r.markVerified();
        assertThat(ranThroughGatedScheduler(r)).as("VERIFIED").isFalse();
        DatastoreReadiness job = new DatastoreReadiness();
        job.markVerified();
        job.markJob();
        job.openWorkers();
        assertThat(ranThroughGatedScheduler(job)).as("JOB").isFalse();
        DatastoreReadiness refused = new DatastoreReadiness();
        refused.markRefused();
        assertThat(ranThroughGatedScheduler(refused)).as("REFUSED").isFalse();
        r.openWorkers();
        assertThat(ranThroughGatedScheduler(r)).as("OPEN").isTrue();
    }
}
