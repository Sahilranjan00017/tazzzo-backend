package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runner, history, lock, target guard, dry-run and verify — against a real MongoDB 7 replica set. */
class MigrationFrameworkIT extends AbstractMigrationIT {

    private static Map<String, MigrationRunner.StepStatus> statuses(MigrationRunner.RunReport r) {
        Map<String, MigrationRunner.StepStatus> m = new LinkedHashMap<>();
        r.steps().forEach(s -> m.put(s.id(), s.status()));
        return m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- fresh DB / repeat / already applied --------------------------------------------------

    @Test
    void a_fresh_database_gets_the_whole_registry_in_order_and_ends_with_the_full_index_catalog() {
        MongoDatabase d = scratch();
        MigrationRunner.RunReport r = realRunner(d).apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(r.ok()).as(r.render()).isTrue();
        Map<String, MigrationRunner.StepStatus> st = statuses(r);
        assertThat(st).containsEntry("V0001__baseline_schema", MigrationRunner.StepStatus.APPLIED_NOW)
                .containsEntry("V0002__products_vertical_id_cursor_index", MigrationRunner.StepStatus.APPLIED_NOW)
                .containsEntry("V0003__taxonomy_seed_0_9_0", MigrationRunner.StepStatus.APPLIED_NOW)
                // the seed already carries the ratified shape, so the data migration finds nothing to change and ADOPTS
                // WITHOUT needing approval
                .containsEntry("V0004__seed_schemas_pack_fields_not_required", MigrationRunner.StepStatus.ADOPTED)
                .containsEntry("V0005__evidence_links_unique_link", MigrationRunner.StepStatus.APPLIED_NOW)
                .containsEntry("V0006__taxonomy_nodes_unique_active_sibling_name", MigrationRunner.StepStatus.APPLIED_NOW);
        assertThat(st.keySet()).as("disabled drop candidates are registered but never run by default")
                .noneMatch(id -> id.startsWith("V01"));
        assertThat(List.copyOf(st.keySet())).isSorted();
        for (IndexSpec s : IndexCatalog.all()) {
            assertThat(s.inspect(d).state()).as(s.describe()).isEqualTo(IndexSpec.State.EXACT);
        }
        assertThat(d.getCollection("taxonomy_nodes").countDocuments()).isEqualTo(460);
        assertThat(history(d, "V0004__seed_schemas_pack_fields_not_required").getBoolean("adopted")).isTrue();
    }

    @Test
    void running_again_is_a_no_op_and_does_not_touch_the_history() {
        MongoDatabase d = scratch();
        MigrationRunner runner = realRunner(d);
        runner.apply(target(d), MigrationRunner.Selection.all(), apply());
        String before = indexSnapshot(d);
        Document h1 = history(d, "V0002__products_vertical_id_cursor_index");

        MigrationRunner.RunReport second = runner.apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(second.ok()).as(second.render()).isTrue();
        assertThat(statuses(second).values()).containsOnly(MigrationRunner.StepStatus.ALREADY_APPLIED);
        assertThat(indexSnapshot(d)).isEqualTo(before);
        Document h2 = history(d, "V0002__products_vertical_id_cursor_index");
        assertThat(h2.get("appliedAt")).isEqualTo(h1.get("appliedAt"));
        assertThat(h2.get("attempts")).isEqualTo(h1.get("attempts"));
        assertThat(h2.getString("runId")).isEqualTo(h1.getString("runId"));
    }

    @Test
    void an_already_applied_migration_is_never_run_again() {
        MongoDatabase d = scratch();
        TestMigration m = new TestMigration("T0001");
        MigrationRunner runner = runner(d, List.of(m));
        runner.apply(target(d), MigrationRunner.Selection.all(), apply());
        runner.apply(target(d), MigrationRunner.Selection.all(), apply());
        runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply()); // a different instance, same history
        assertThat(m.applies.get()).isEqualTo(1);
    }

    @Test
    void migrations_run_in_id_order_whatever_the_registration_order() {
        MongoDatabase d = scratch();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        TestMigration a = new TestMigration("T0001");
        TestMigration b = new TestMigration("T0002");
        TestMigration c = new TestMigration("T0003");
        a.action = () -> order.add("T0001");
        b.action = () -> order.add("T0002");
        c.action = () -> order.add("T0003");
        runner(d, List.of(c, a, b)).apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(order).containsExactly("T0001", "T0002", "T0003");
    }

    @Test
    void editing_an_applied_migration_is_detected_not_silently_ignored() {
        MongoDatabase d = scratch();
        TestMigration original = new TestMigration("T0001");
        runner(d, List.of(original)).apply(target(d), MigrationRunner.Selection.all(), apply());

        TestMigration edited = new TestMigration("T0001");
        edited.definition = "v2 — somebody changed what this migration does";
        MigrationRunner r = runner(d, List.of(edited));
        MigrationRunner.RunReport report = r.apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(report.outcome()).isEqualTo(MigrationRunner.Outcome.CHECKSUM_MISMATCH);
        assertThat(edited.applies.get()).isZero();
        assertThat(r.verify(MigrationRunner.Selection.all()).ok()).isFalse();
        assertThat(r.dryRun(target(d), MigrationRunner.Selection.all()).outcome())
                .isEqualTo(MigrationRunner.Outcome.CHECKSUM_MISMATCH);
    }

    // ---- failures, blocked, validation --------------------------------------------------------

    @Test
    void a_partial_failure_stops_the_run_records_it_without_secrets_and_a_retry_resumes() {
        MongoDatabase d = scratch();
        TestMigration ok = new TestMigration("T0001");
        TestMigration flaky = new TestMigration("T0002");
        TestMigration later = new TestMigration("T0003");
        flaky.action = () -> {
            throw new IllegalStateException("cannot reach mongodb://admin:s3cr3tpw@db.example.internal:27017/app?tls=true password=hunter2");
        };
        MigrationRunner runner = runner(d, List.of(ok, flaky, later));

        MigrationRunner.RunReport first = runner.apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(first.outcome()).isEqualTo(MigrationRunner.Outcome.FAILED);
        assertThat(statuses(first)).containsEntry("T0001", MigrationRunner.StepStatus.APPLIED_NOW)
                .containsEntry("T0002", MigrationRunner.StepStatus.FAILED)
                .containsEntry("T0003", MigrationRunner.StepStatus.NOT_RUN);
        Document failed = history(d, "T0002");
        assertThat(failed.getString("status")).isEqualTo("FAILED");
        String error = failed.getString("error");
        assertThat(error).contains("IllegalStateException").doesNotContain("s3cr3tpw").doesNotContain("hunter2")
                .doesNotContain("admin:").doesNotContain("db.example.internal");
        assertThat(first.render()).doesNotContain("s3cr3tpw").doesNotContain("hunter2");
        assertThat(history(d, "T0003")).as("a migration that did not run has no history").isNull();
        assertThat(later.applies.get()).isZero();

        flaky.action = () -> { };
        MigrationRunner.RunReport retry = runner.apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(retry.ok()).as(retry.render()).isTrue();
        assertThat(ok.applies.get()).as("the already-applied migration is not repeated").isEqualTo(1);
        assertThat(history(d, "T0002").getString("status")).isEqualTo("APPLIED");
        assertThat(history(d, "T0002").getInteger("attempts")).as("the retry is counted").isEqualTo(2);
        assertThat(history(d, "T0002").containsKey("error")).as("the stale error is cleared").isFalse();
        assertThat(later.applies.get()).isEqualTo(1);
    }

    @Test
    void a_failed_post_validation_marks_the_migration_failed_and_stops() {
        MongoDatabase d = scratch();
        TestMigration m = new TestMigration("T0001");
        m.validation = () -> List.of("expected index is missing");
        TestMigration after = new TestMigration("T0002");
        MigrationRunner.RunReport r = runner(d, List.of(m, after)).apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.FAILED);
        assertThat(history(d, "T0001").getString("status")).isEqualTo("FAILED");
        assertThat(history(d, "T0001").getString("error")).contains("post-validation failed");
        assertThat(after.applies.get()).isZero();
    }

    @Test
    void a_blocked_preflight_stops_the_run_records_the_blockers_and_changes_nothing() {
        MongoDatabase d = scratch();
        TestMigration blocked = new TestMigration("T0001");
        blocked.preflight = Preflight.blocked(List.of("duplicates exist; operator decision required"));
        TestMigration after = new TestMigration("T0002");
        MigrationRunner runner = runner(d, List.of(blocked, after));

        MigrationRunner.RunReport r = runner.apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(blocked.applies.get()).isZero();
        assertThat(after.applies.get()).isZero();
        assertThat(statuses(r)).containsEntry("T0002", MigrationRunner.StepStatus.NOT_RUN);
        assertThat(history(d, "T0001").getString("status")).isEqualTo("BLOCKED");
        assertThat(history(d, "T0001").getList("blockers", String.class)).containsExactly("duplicates exist; operator decision required");

        blocked.preflight = Preflight.ready(List.of("op"), List.of());
        assertThat(runner.apply(target(d), MigrationRunner.Selection.all(), apply()).ok()).isTrue();
        assertThat(history(d, "T0001").getString("status")).isEqualTo("APPLIED");
    }

    @Test
    void a_state_that_already_holds_is_adopted_without_applying() {
        MongoDatabase d = scratch();
        TestMigration m = new TestMigration("T0001");
        m.preflight = Preflight.satisfied("already there");
        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(statuses(r)).containsEntry("T0001", MigrationRunner.StepStatus.ADOPTED);
        assertThat(m.applies.get()).isZero();
        assertThat(history(d, "T0001").getBoolean("adopted")).isTrue();
        assertThat(history(d, "T0001").getString("status")).isEqualTo("APPLIED");
    }

    // ---- approval and enablement --------------------------------------------------------------

    @Test
    void a_data_migration_never_runs_without_explicit_approval() {
        MongoDatabase d = scratch();
        TestMigration data = new TestMigration("T0001", MigrationKind.DATA);
        MigrationRunner runner = runner(d, List.of(data));

        MigrationRunner.RunReport unapproved = runner.apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(statuses(unapproved)).containsEntry("T0001", MigrationRunner.StepStatus.PENDING_APPROVAL);
        assertThat(data.applies.get()).isZero();
        assertThat(history(d, "T0001")).as("nothing is recorded for a migration that was not approved").isNull();
        MigrationRunner.VerifyReport v = runner.verify(MigrationRunner.Selection.all());
        assertThat(v.ok()).as("an unapproved data migration is not required to serve").isTrue();
        assertThat(v.pendingApproval()).containsExactly("T0001");

        MigrationRunner.RunReport approved = runner.apply(target(d), MigrationRunner.Selection.all().withApproved(Set.of("T0001")), apply());
        assertThat(statuses(approved)).containsEntry("T0001", MigrationRunner.StepStatus.APPLIED_NOW);
        assertThat(data.applies.get()).isEqualTo(1);
    }

    @Test
    void a_disabled_migration_runs_only_when_explicitly_enabled() {
        MongoDatabase d = scratch();
        TestMigration m = new TestMigration("T0001");
        m.enabledByDefault = false;
        MigrationRunner runner = runner(d, List.of(m));
        assertThat(runner.apply(target(d), MigrationRunner.Selection.all(), apply()).steps()).isEmpty();
        assertThat(m.applies.get()).isZero();
        runner.apply(target(d), MigrationRunner.Selection.all().withEnabled(Set.of("T0001")), apply());
        assertThat(m.applies.get()).isEqualTo(1);
    }

    // ---- history -------------------------------------------------------------------------------

    @Test
    void the_history_records_who_what_where_and_when_without_secrets() {
        MongoDatabase d = scratch();
        TestMigration m = new TestMigration("T0001");
        runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(), apply());
        Document h = history(d, "T0001");
        assertThat(h.getString("status")).isEqualTo("APPLIED");
        assertThat(h.getString("checksum")).isEqualTo(m.checksum()).hasSize(64);
        assertThat(h.getString("environment")).isEqualTo("test");
        assertThat(h.getString("database")).isEqualTo(d.getName());
        assertThat(h.getString("operator")).isEqualTo("tests");
        assertThat(h.getString("buildVersion")).isEqualTo("build-1");
        assertThat(h.getString("runId")).isNotBlank();
        assertThat(h.getLong("fence")).isGreaterThanOrEqualTo(1);
        assertThat(h.getDate("appliedAt")).isNotNull();
        assertThat(h.getDate("startedAt")).isNotNull();
        assertThat(h.getLong("durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(h.getInteger("attempts")).isEqualTo(1);
        assertThat(h.getBoolean("adopted")).isFalse();
        assertThat(h.toJson()).doesNotContain("mongodb://").doesNotContain("password");
    }

    @Test
    void a_stale_lock_holder_cannot_overwrite_a_newer_holders_history() {
        MongoDatabase d = scratch();
        MigrationHistory h = new MigrationHistory(d);
        h.ensureExists();
        TestMigration m = new TestMigration("T0001");
        h.markApplied(m, new MigrationHistory.RunInfo("run-new", 2L, target(d)), new Date(), "new", null, 1, false);

        assertThatThrownBy(() -> h.markApplied(m, new MigrationHistory.RunInfo("run-old", 1L, target(d)), new Date(), "stale", null, 1, false))
                .isInstanceOf(LockLostException.class);
        assertThat(history(d, "T0001").getString("runId")).isEqualTo("run-new");
        assertThat(history(d, "T0001").getString("note")).isEqualTo("new");
    }

    // ---- the lock -------------------------------------------------------------------------------

    @Test
    void concurrent_runners_apply_a_migration_exactly_once() throws Exception {
        MongoDatabase d = scratch();
        TestMigration slow = new TestMigration("T0001");
        slow.action = () -> sleep(500);
        int runners = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runners);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MigrationRunner.RunReport>> futures = new ArrayList<>();
        for (int i = 0; i < runners; i++) {
            futures.add(pool.submit(() -> {
                MigrationRunner r = runner(d, List.of(slow)); // each runner is its own lock owner
                go.await();
                return r.apply(target(d), MigrationRunner.Selection.all(), apply(Duration.ofSeconds(30), Duration.ofSeconds(60)));
            }));
        }
        go.countDown();
        int appliedNow = 0, alreadyApplied = 0;
        for (Future<MigrationRunner.RunReport> f : futures) {
            MigrationRunner.RunReport r = f.get(90, TimeUnit.SECONDS);
            assertThat(r.ok()).as(r.render()).isTrue();
            MigrationRunner.StepStatus s = statuses(r).get("T0001");
            if (s == MigrationRunner.StepStatus.APPLIED_NOW) appliedNow++;
            else if (s == MigrationRunner.StepStatus.ALREADY_APPLIED) alreadyApplied++;
        }
        pool.shutdown();
        assertThat(slow.applies.get()).as("the migration body ran exactly once across all runners").isEqualTo(1);
        assertThat(appliedNow).isEqualTo(1);
        assertThat(alreadyApplied).isEqualTo(runners - 1);
    }

    @Test
    void a_second_runner_that_cannot_wait_is_refused_while_the_lock_is_held() throws Exception {
        MongoDatabase d = scratch();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestMigration holder = new TestMigration("T0001");
        holder.action = () -> {
            started.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<MigrationRunner.RunReport> first = pool.submit(() ->
                runner(d, List.of(holder)).apply(target(d), MigrationRunner.Selection.all(), apply()));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        TestMigration other = new TestMigration("T0001");
        MigrationRunner.RunReport refused = runner(d, List.of(other)).apply(target(d), MigrationRunner.Selection.all(),
                apply(Duration.ofMinutes(5), Duration.ZERO));
        release.countDown();

        assertThat(refused.outcome()).isEqualTo(MigrationRunner.Outcome.LOCK_HELD);
        assertThat(refused.steps()).isEmpty();
        assertThat(other.applies.get()).isZero();
        assertThat(first.get(30, TimeUnit.SECONDS).ok()).isTrue();
        pool.shutdown();
    }

    @Test
    void the_lock_is_released_after_a_failure_so_the_next_run_proceeds_immediately() {
        MongoDatabase d = scratch();
        TestMigration failing = new TestMigration("T0001");
        failing.action = () -> { throw new IllegalStateException("boom"); };
        MigrationLock lock = new MigrationLock(d);

        runner(d, List.of(failing)).apply(target(d), MigrationRunner.Selection.all(), apply());

        assertThat(lock.current().orElseThrow().get("ownerId")).as("released after the failed run").isNull();
        failing.action = () -> { };
        MigrationRunner.RunReport next = runner(d, List.of(failing)).apply(target(d), MigrationRunner.Selection.all(),
                apply(Duration.ofMinutes(5), Duration.ZERO));
        assertThat(next.ok()).as(next.render()).isTrue();
    }

    @Test
    void a_crashed_holders_lease_expires_and_is_taken_over_with_a_higher_fence() {
        MongoDatabase d = scratch();
        d.createCollection(MigrationLock.COLLECTION);
        d.getCollection(MigrationLock.COLLECTION).insertOne(new Document("_id", MigrationLock.LOCK_ID)
                .append("ownerId", "crashed-process").append("fence", 5L)
                .append("expiresAt", new Date(System.currentTimeMillis() - 60_000)));
        TestMigration m = new TestMigration("T0001");

        MigrationRunner.RunReport r = runner(d, List.of(m)).apply(target(d), MigrationRunner.Selection.all(),
                apply(Duration.ofMinutes(5), Duration.ZERO));

        assertThat(r.ok()).as(r.render()).isTrue();
        assertThat(history(d, "T0001").getLong("fence")).as("fencing token increases on every acquisition").isEqualTo(6L);
    }

    @Test
    void a_live_lease_cannot_be_taken_over() {
        MongoDatabase d = scratch();
        MigrationLock lock = new MigrationLock(d);
        assertThat(lock.tryAcquire("owner-a", "run-a", Duration.ofMinutes(5))).isPresent();
        assertThat(lock.tryAcquire("owner-b", "run-b", Duration.ofMinutes(5))).as("a live lease is exclusive").isEmpty();
        MigrationLock.Held a = new MigrationLock.Held("owner-a", 1L);
        lock.release(a);
        assertThat(lock.tryAcquire("owner-b", "run-b", Duration.ofMinutes(5))).isPresent();
    }

    @Test
    void losing_the_lock_mid_run_stops_without_recording_the_migration_as_applied() {
        MongoDatabase d = scratch();
        TestMigration m = new TestMigration("T0001");
        TestMigration after = new TestMigration("T0002");
        m.action = () -> {
            // another process takes the lock over while this migration is running
            d.getCollection(MigrationLock.COLLECTION).updateOne(new Document("_id", MigrationLock.LOCK_ID),
                    new Document("$set", new Document("ownerId", "thief").append("fence", 99L)
                            .append("expiresAt", new Date(System.currentTimeMillis() + 600_000))));
            sleep(800); // longer than the heartbeat period (lease/3), so the failed renewal is observed
        };
        MigrationRunner.RunReport r = runner(d, List.of(m, after)).apply(target(d), MigrationRunner.Selection.all(),
                apply(Duration.ofMillis(300), Duration.ZERO));

        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.LOCK_LOST);
        assertThat(history(d, "T0001").getString("status")).as("never recorded as APPLIED after losing the lock").isEqualTo("APPLYING");
        assertThat(after.applies.get()).isZero();
        assertThat(new MigrationLock(d).current().orElseThrow().getString("ownerId")).as("the new holder keeps its lock").isEqualTo("thief");
    }

    // ---- environment safety --------------------------------------------------------------------

    private MigrationRunner.RunReport applyWith(MongoDatabase d, MigrationTarget t, MigrationMode mode, String confirmDb, String confirmEnv) {
        return runner(d, List.of(new TestMigration("T0001"))).apply(t, MigrationRunner.Selection.all(),
                new MigrationRunner.ApplyOptions(mode, confirmDb, confirmEnv, Duration.ofMinutes(5), Duration.ZERO));
    }

    @Test
    void an_unidentified_or_unknown_environment_is_refused_before_anything_is_created() {
        MongoDatabase d = scratch();
        MigrationRunner.RunReport blank = applyWith(d, new MigrationTarget("", d.getName(), List.of(), "op", "b"), MigrationMode.APPLY, "", "");
        MigrationRunner.RunReport unknown = applyWith(d, new MigrationTarget("prod", d.getName(), List.of(), "op", "b"), MigrationMode.APPLY, d.getName(), "prod");
        assertThat(blank.outcome()).isEqualTo(MigrationRunner.Outcome.TARGET_REFUSED);
        assertThat(unknown.outcome()).isEqualTo(MigrationRunner.Outcome.TARGET_REFUSED);
        assertThat(collectionNames(d)).as("a refused run creates nothing (no history, no lock)").isEmpty();
    }

    @Test
    void production_apply_needs_the_two_key_confirmation() {
        MongoDatabase d = scratch();
        MigrationTarget prod = new MigrationTarget("production", d.getName(), List.of(), "ci-job", "b");
        assertThat(applyWith(d, prod, MigrationMode.APPLY, "", "").outcome()).isEqualTo(MigrationRunner.Outcome.TARGET_REFUSED);
        assertThat(applyWith(d, prod, MigrationMode.APPLY, d.getName(), "").outcome()).isEqualTo(MigrationRunner.Outcome.TARGET_REFUSED);
        assertThat(applyWith(d, prod, MigrationMode.APPLY, "some-other-db", "production").outcome()).isEqualTo(MigrationRunner.Outcome.TARGET_REFUSED);
        assertThat(applyWith(d, prod, MigrationMode.APPLY, d.getName(), "staging").outcome()).isEqualTo(MigrationRunner.Outcome.TARGET_REFUSED);
        assertThat(collectionNames(d)).isEmpty();
        assertThat(applyWith(d, prod, MigrationMode.APPLY, d.getName(), "production").ok()).isTrue();
    }

    @Test
    void startup_mutation_is_never_allowed_in_staging_or_production() {
        MongoDatabase d = scratch();
        for (String env : List.of("staging", "production")) {
            MigrationTarget t = new MigrationTarget(env, d.getName(), List.of(), "op", "b");
            assertThat(applyWith(d, t, MigrationMode.APPLY_ON_STARTUP, d.getName(), env).outcome()).isEqualTo(MigrationRunner.Outcome.TARGET_REFUSED);
            assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.LEGACY, t, null, null))
                    .isInstanceOf(TargetRefusedException.class);
        }
        assertThat(collectionNames(d)).isEmpty();
    }

    @Test
    void self_serve_environments_need_no_confirmation() {
        MongoDatabase d = scratch();
        for (String env : List.of("local", "test", "dev")) {
            assertThat(applyWith(d, new MigrationTarget(env, d.getName(), List.of(), "op", "b"), MigrationMode.APPLY_ON_STARTUP, null, null).ok()).as(env).isTrue();
        }
    }

    // ---- dry run and verify --------------------------------------------------------------------

    @Test
    void a_dry_run_mutates_nothing_and_reports_what_would_happen() {
        MongoDatabase d = scratch();
        MigrationRunner.RunReport r = realRunner(d).dryRun(target(d), MigrationRunner.Selection.all());

        assertThat(collectionNames(d)).as("no collection — not even the history or the lock — is created").isEmpty();
        assertThat(r.dryRun()).isTrue();
        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.OK);
        Map<String, MigrationRunner.StepStatus> st = statuses(r);
        assertThat(st).containsEntry("V0001__baseline_schema", MigrationRunner.StepStatus.WOULD_APPLY)
                .containsEntry("V0003__taxonomy_seed_0_9_0", MigrationRunner.StepStatus.WOULD_APPLY)
                .containsEntry("V0004__seed_schemas_pack_fields_not_required", MigrationRunner.StepStatus.WOULD_ADOPT);
        MigrationRunner.Step baseline = r.steps().get(0);
        assertThat(baseline.wouldMutate()).isTrue();
        assertThat(baseline.operations()).isNotEmpty();
        assertThat(r.target()).contains("environment=test").contains("database=" + d.getName()).doesNotContain("mongodb://");
        assertThat(r.render()).contains("DRY-RUN").contains("V0001__baseline_schema");
    }

    @Test
    void a_dry_run_after_a_full_apply_reports_everything_already_applied() {
        MongoDatabase d = scratch();
        MigrationRunner runner = realRunner(d);
        runner.apply(target(d), MigrationRunner.Selection.all(), apply());
        String before = indexSnapshot(d);
        MigrationRunner.RunReport r = runner.dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(statuses(r).values()).containsOnly(MigrationRunner.StepStatus.ALREADY_APPLIED);
        assertThat(indexSnapshot(d)).isEqualTo(before);
    }

    @Test
    void a_dry_run_reports_blockers_without_recording_anything() {
        MongoDatabase d = scratch();
        TestMigration blocked = new TestMigration("T0001");
        blocked.preflight = Preflight.blocked(List.of("conflict"));
        MigrationRunner.RunReport r = runner(d, List.of(blocked)).dryRun(target(d), MigrationRunner.Selection.all());
        assertThat(r.outcome()).isEqualTo(MigrationRunner.Outcome.BLOCKED);
        assertThat(r.steps().get(0).blockers()).containsExactly("conflict");
        assertThat(collectionNames(d)).isEmpty();
    }

    @Test
    void verify_is_read_only_and_reports_exactly_what_is_missing() {
        MongoDatabase d = scratch();
        MigrationRunner runner = realRunner(d);

        MigrationRunner.VerifyReport fresh = runner.verify(MigrationRunner.Selection.all());
        assertThat(fresh.ok()).isFalse();
        assertThat(fresh.problems()).anyMatch(p -> p.contains("not migrated"));
        assertThat(collectionNames(d)).as("verify creates nothing").isEmpty();

        runner.apply(target(d), MigrationRunner.Selection.all(), apply());
        assertThat(runner.verify(MigrationRunner.Selection.all()).ok()).isTrue();

        d.getCollection(MigrationHistory.COLLECTION).updateOne(new Document("_id", "V0002__products_vertical_id_cursor_index"),
                new Document("$set", new Document("status", "FAILED").append("error", "disk full")));
        MigrationRunner.VerifyReport failed = runner.verify(MigrationRunner.Selection.all());
        assertThat(failed.ok()).isFalse();
        assertThat(failed.problems()).anyMatch(p -> p.contains("V0002") && p.contains("FAILED"));

        d.getCollection(MigrationHistory.COLLECTION).deleteOne(new Document("_id", "V0005__evidence_links_unique_link"));
        assertThat(runner.verify(MigrationRunner.Selection.all()).problems())
                .anyMatch(p -> p.contains("V0005") && p.contains("not applied"));
    }

    // ---- sanitizer ------------------------------------------------------------------------------

    @Test
    void failure_text_never_carries_connection_strings_or_credentials() {
        String raw = "Timed out connecting to mongodb+srv://svc:p%40ss@cluster0.abcde.mongodb.net/db?retryWrites=true; "
                + "token=abc123 apikey: XYZ secret=shh";
        String safe = MigrationSanitizer.sanitize(raw);
        assertThat(safe).doesNotContain("p%40ss").doesNotContain("svc:").doesNotContain("cluster0").doesNotContain("abc123")
                .doesNotContain("XYZ").doesNotContain("shh");
        assertThat(MigrationSanitizer.sanitize("x".repeat(2000)).length()).isLessThanOrEqualTo(501);
    }
}
