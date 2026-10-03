package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the application does at startup in each mode (R5): verify by default, mutate only deliberately. */
class MigrationStartupRunnerIT extends AbstractMigrationIT {

    @Autowired DiscriminatingAttributeRegistry discriminators;

    private final int[] exitCode = {-1};

    private MigrationStartupRunner startup(MongoDatabase d, MigrationProperties props, boolean legacyBootstrap, boolean legacySeed) {
        return new MigrationStartupRunner(realRunner(d), props, client, d, schemaBootstrap, taxonomyLoader, discriminators,
                legacyBootstrap, legacySeed, code -> exitCode[0] = code);
    }

    private static MigrationProperties props(MigrationMode mode, String environment) {
        MigrationProperties p = new MigrationProperties();
        p.setMode(mode);
        p.setEnvironment(environment);
        return p;
    }

    @Test
    void the_default_mode_is_a_read_only_verification_that_fails_closed() {
        assertThat(new MigrationProperties().getMode()).isEqualTo(MigrationMode.VERIFY);
        assertThat(new MigrationProperties().getVerifyFailure()).isEqualTo(MigrationProperties.VerifyFailure.FAIL);
        assertThat(new MigrationProperties().isExitAfterRun()).isFalse();
    }

    @Test
    void verify_refuses_to_start_on_an_unmigrated_database_and_creates_nothing() {
        MongoDatabase d = scratch();
        assertThatThrownBy(() -> startup(d, props(MigrationMode.VERIFY, "production"), false, false).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start").hasMessageContaining("migration job");
        assertThat(collectionNames(d)).isEmpty();
    }

    @Test
    void verify_with_warn_continues_and_with_a_migrated_database_passes() {
        MongoDatabase d = scratch();
        MigrationProperties warn = props(MigrationMode.VERIFY, "production");
        warn.setVerifyFailure(MigrationProperties.VerifyFailure.WARN);
        startup(d, warn, false, false).run(null); // does not throw
        assertThat(collectionNames(d)).as("still nothing created").isEmpty();

        realRunner(d).apply(target(d), MigrationRunner.Selection.all(), apply());
        startup(d, props(MigrationMode.VERIFY, "production"), false, false).run(null); // passes
    }

    @Test
    void apply_on_startup_works_in_local_and_is_refused_in_production() {
        MongoDatabase local = scratch();
        startup(local, props(MigrationMode.APPLY_ON_STARTUP, "local"), false, false).run(null);
        assertThat(history(local, "V0001__baseline_schema").getString("status")).isEqualTo("APPLIED");
        assertThat(realRunner(local).verify(MigrationRunner.Selection.all()).ok()).isTrue();

        MongoDatabase prod = scratch();
        assertThatThrownBy(() -> startup(prod, props(MigrationMode.APPLY_ON_STARTUP, "production"), false, false).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TARGET_REFUSED");
        assertThat(collectionNames(prod)).isEmpty();
    }

    @Test
    void legacy_flag_behaviour_is_for_local_test_dev_only() {
        MongoDatabase d = scratch();
        startup(d, props(MigrationMode.LEGACY, "test"), true, false).run(null);
        assertThat(collectionNames(d)).contains("products", "orders");

        MongoDatabase prod = scratch();
        assertThatThrownBy(() -> startup(prod, props(MigrationMode.LEGACY, "production"), true, false).run(null))
                .isInstanceOf(TargetRefusedException.class);
        assertThatThrownBy(() -> startup(prod, props(MigrationMode.LEGACY, "staging"), false, true).run(null))
                .isInstanceOf(TargetRefusedException.class);
        assertThat(collectionNames(prod)).isEmpty();
        // LEGACY with no mutating flag mutates nothing, so it is not refused anywhere
        startup(prod, props(MigrationMode.LEGACY, "production"), false, false).run(null);
    }

    @Test
    void a_dry_run_job_reports_and_exits_without_mutating() {
        MongoDatabase d = scratch();
        MigrationProperties p = props(MigrationMode.DRY_RUN, "production");
        p.setExitAfterRun(true);
        startup(d, p, false, false).run(null);
        assertThat(exitCode[0]).isZero();
        assertThat(collectionNames(d)).isEmpty();
    }

    @Test
    void a_dry_run_job_with_a_blocker_exits_non_zero() {
        MongoDatabase d = scratch();
        d.getCollection("customers").createIndex(new Document("phoneNormalized", 1), new IndexOptions().name("customer_one_per_phone")); // conflicts
        MigrationProperties p = props(MigrationMode.DRY_RUN, "staging");
        p.setExitAfterRun(true);
        startup(d, p, false, false).run(null);
        assertThat(exitCode[0]).isEqualTo(2);
        assertThat(collectionNames(d)).containsExactly("customers");
    }

    @Test
    void an_apply_job_outside_self_serve_environments_needs_the_two_key_confirmation() {
        MongoDatabase d = scratch();
        MigrationProperties refused = props(MigrationMode.APPLY, "staging");
        refused.setExitAfterRun(true);
        startup(d, refused, false, false).run(null);
        assertThat(exitCode[0]).as("TARGET_REFUSED").isEqualTo(7);
        assertThat(collectionNames(d)).isEmpty();

        MigrationProperties confirmed = props(MigrationMode.APPLY, "staging");
        confirmed.setExitAfterRun(true);
        confirmed.setConfirmDatabase(d.getName());
        confirmed.setConfirmEnvironment("staging");
        confirmed.setOperator("ci-deploy-job-42");
        confirmed.setBuildVersion("1.2.3");
        startup(d, confirmed, false, false).run(null);
        assertThat(exitCode[0]).isZero();
        assertThat(history(d, "V0001__baseline_schema").getString("operator")).isEqualTo("ci-deploy-job-42");
        assertThat(history(d, "V0001__baseline_schema").getString("buildVersion")).isEqualTo("1.2.3");
        assertThat(history(d, "V0001__baseline_schema").getString("environment")).isEqualTo("staging");
    }

    @Test
    void a_job_that_fails_without_exit_after_run_stops_the_application() {
        MongoDatabase d = scratch();
        assertThatThrownBy(() -> startup(d, props(MigrationMode.APPLY, "staging"), false, false).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exit code 7");
    }

    @Test
    void the_target_never_exposes_a_connection_string() {
        MongoDatabase d = scratch();
        MigrationTarget t = startup(d, props(MigrationMode.VERIFY, "test"), false, false).target();
        assertThat(t.hosts()).isNotEmpty();
        assertThat(t.describe()).doesNotContain("mongodb://").doesNotContain("@");
    }
}
