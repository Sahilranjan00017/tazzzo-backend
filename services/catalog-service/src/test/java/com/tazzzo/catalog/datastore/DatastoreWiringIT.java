package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.CatalogApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.ArrayList;
import java.util.List;

import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.DB;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.MIGRATOR_USER;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.USER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The verifier is wired and ordered BEFORE the migration startup runner: a staging or production process whose connection
 * string violates the contract is refused before the migration runner (or any other ApplicationRunner) can mutate the
 * database, even when it holds credentials that could apply a migration and every two-key confirmation is correct.
 *
 * <p>Scope of that claim: ordering alone does NOT hold back {@code @Scheduled} workers, which start at context refresh.
 * These tests run with the scheduler disabled; the scheduled-worker guarantee is the readiness gate
 * ({@code DatastoreReadiness} / {@code GatedTaskScheduler}) and is proven, with the scheduler ENABLED, by
 * {@code StartupSchedulerGateIT} and {@code ScheduledWorkerGateTest}.
 */
class DatastoreWiringIT {

    @BeforeAll
    static void up() {
        AuthenticatedReplicaSet.start();
    }

    private static String[] args(String uri, String... extra) {
        List<String> a = new ArrayList<>(List.of(
                "--spring.data.mongodb.uri=" + uri,
                "--spring.data.mongodb.database=" + DB,
                "--tazzzo.schema.load-taxonomy-seed=false",
                "--tazzzo.scheduler.enabled=false",
                "--tazzzo.consumer-rate-limit.mode=DISABLED"));
        a.addAll(List.of(extra));
        return a.toArray(String[]::new);
    }

    @Test
    void a_fully_authorized_staging_apply_with_a_non_compliant_uri_is_refused_before_the_migration_runner_mutates_anything() {
        AuthenticatedReplicaSet.resetDatabase();
        String uri = AuthenticatedReplicaSet.uri(MIGRATOR_USER, USER_PASSWORD, DB); // directConnection, no TLS, loopback, no explicit options
        assertThatThrownBy(() -> new SpringApplicationBuilder(CatalogApplication.class).web(WebApplicationType.NONE).run(args(uri,
                "--tazzzo.migration.mode=APPLY", "--tazzzo.migration.environment=staging",
                "--tazzzo.migration.confirm-database=" + DB, "--tazzzo.migration.confirm-environment=staging",
                "--tazzzo.migration.exit-after-run=false")))
                .isInstanceOf(DatastoreContractException.class)
                .hasMessageContaining("refusing to start")
                .hasMessageContaining("TLS_REQUIRED")
                .hasMessageContaining("LOOPBACK_HOST_FORBIDDEN");
        assertThat(AuthenticatedReplicaSet.root().getDatabase(DB).listCollectionNames()).as("nothing was created or migrated").isEmpty();
    }

    @Test
    void the_refusal_message_does_not_leak_the_credentials() {
        String uri = AuthenticatedReplicaSet.uri(MIGRATOR_USER, USER_PASSWORD, DB);
        assertThatThrownBy(() -> new SpringApplicationBuilder(CatalogApplication.class).web(WebApplicationType.NONE).run(args(uri,
                "--tazzzo.migration.mode=VERIFY", "--tazzzo.migration.environment=production")))
                .isInstanceOf(DatastoreContractException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(USER_PASSWORD).doesNotContain(MIGRATOR_USER).doesNotContain("authSource"));
    }

    @Test
    void production_with_no_configured_uri_fails_fast_instead_of_falling_back_to_localhost() {
        long start = System.nanoTime();
        // no spring.data.mongodb.uri: application.yml's localhost default applies, which is exactly the misconfiguration to catch
        assertThatThrownBy(() -> new SpringApplicationBuilder(CatalogApplication.class).web(WebApplicationType.NONE).run(
                "--tazzzo.schema.load-taxonomy-seed=false", "--tazzzo.scheduler.enabled=false", "--tazzzo.consumer-rate-limit.mode=DISABLED",
                "--tazzzo.migration.mode=VERIFY", "--tazzzo.migration.environment=production"))
                .isInstanceOf(DatastoreContractException.class)
                .hasMessageContaining("LOOPBACK_HOST_FORBIDDEN")
                .hasMessageContaining("CREDENTIALS_REQUIRED");
        assertThat((System.nanoTime() - start) / 1_000_000_000L).as("seconds: no connection attempt, so no server-selection wait").isLessThan(20);
    }

    @Test
    void an_unset_environment_with_a_remote_uri_is_refused() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CatalogApplication.class).web(WebApplicationType.NONE).run(
                "--spring.data.mongodb.uri=mongodb://appuser:pw@db.example.net:27017/tazzzo?replicaSet=rs0",
                "--tazzzo.schema.load-taxonomy-seed=false", "--tazzzo.scheduler.enabled=false", "--tazzzo.consumer-rate-limit.mode=DISABLED",
                "--tazzzo.migration.mode=VERIFY", "--tazzzo.migration.environment="))
                .isInstanceOf(DatastoreContractException.class)
                .hasMessageContaining("ENVIRONMENT_NOT_IDENTIFIED");
    }

    @Test
    void a_remote_target_labelled_dev_test_or_local_is_refused_exactly_like_production() {
        for (String env : new String[]{"dev", "test", "local"}) {
            long start = System.nanoTime();
            // the label is metadata: a remote cluster that states none of the contract is refused whatever it is called
            assertThatThrownBy(() -> new SpringApplicationBuilder(CatalogApplication.class).web(WebApplicationType.NONE).run(
                    "--spring.data.mongodb.uri=mongodb+srv://appuser:pw@cluster0.abcde.mongodb.net/tazzzo",
                    "--tazzzo.schema.load-taxonomy-seed=false", "--tazzzo.scheduler.enabled=false", "--tazzzo.consumer-rate-limit.mode=DISABLED",
                    "--tazzzo.migration.mode=APPLY_ON_STARTUP", "--tazzzo.migration.environment=" + env))
                    .as(env).isInstanceOf(DatastoreContractException.class)
                    .hasMessageContaining("RETRY_WRITES_REQUIRED").hasMessageContaining("WRITE_CONCERN_MAJORITY_REQUIRED");
            assertThat((System.nanoTime() - start) / 1_000_000_000L).as(env + ": refused before any connection").isLessThan(20);
        }
    }
}
