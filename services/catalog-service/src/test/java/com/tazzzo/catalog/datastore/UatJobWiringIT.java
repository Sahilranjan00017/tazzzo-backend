package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.migration.MigrationExitHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The UAT migration job shape (environment {@code uat}, one-shot non-web DRY_RUN with {@code exit-after-run=true}, only the
 * runbook's configuration) through the REAL Spring wiring: the datastore verifier is enforced for {@code uat} and refuses
 * before the migration runner (no exit code is ever produced), and the refusal never echoes credentials. No database is
 * needed: every case is refused before any command is sent (the driver's own background monitors may still try to reach
 * the hosts; the reserved {@code .invalid} names never resolve). The exit action is observed, never executed.
 */
class UatJobWiringIT {

    static final List<Integer> EXIT_CODES = new CopyOnWriteArrayList<>();

    @Configuration
    static class RecordingExit {
        @Bean
        @Primary
        MigrationExitHandler recordingExit() {
            return EXIT_CODES::add;
        }
    }

    private static String[] job(String uri, String mode) {
        return new String[]{
                "--spring.mongodb.uri=" + uri,
                "--spring.mongodb.database=tazzzo_uat",
                "--tazzzo.migration.mode=" + mode,
                "--tazzzo.migration.environment=uat",
                "--tazzzo.migration.exit-after-run=true",
                "--tazzzo.schema.load-taxonomy-seed=false",
                "--tazzzo.scheduler.enabled=false",
                "--tazzzo.consumer-rate-limit.mode=DISABLED"};
    }

    private static SpringApplicationBuilder app() {
        return new SpringApplicationBuilder(CatalogApplication.class, RecordingExit.class).web(WebApplicationType.NONE);
    }

    @Test
    void a_uat_dry_run_job_with_a_non_compliant_uri_is_refused_by_the_verifier_before_the_migration_runner() {
        EXIT_CODES.clear();
        long start = System.nanoTime();
        assertThatThrownBy(() -> app().run(job("mongodb://localhost:27017/tazzzo_uat?replicaSet=rs0", "DRY_RUN")))
                .isInstanceOf(DatastoreContractException.class)
                .hasMessageContaining("environment=uat")
                .hasMessageContaining("profile=MIGRATION_READ")
                .hasMessageContaining("LOOPBACK_HOST_FORBIDDEN")
                .hasMessageContaining("TLS_REQUIRED")
                .hasMessageContaining("CREDENTIALS_REQUIRED");
        assertThat(EXIT_CODES).as("the migration runner never ran, so no job exit code was produced").isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000_000L).as("seconds: refused before any server selection").isLessThan(20);
    }

    @Test
    void a_compliant_uat_uri_is_enforced_and_fails_closed_at_the_live_stage_without_leaking_credentials() {
        EXIT_CODES.clear();
        String uri = "mongodb://appuser:s3cretPW@db1.uat-wiring.invalid:27017,db2.uat-wiring.invalid:27017/tazzzo_uat"
                + "?replicaSet=rs0&tls=true&authSource=admin&" + ConnectionContractTest.OPTIONS.replace("serverSelectionTimeoutMS=15000",
                "serverSelectionTimeoutMS=1000");
        assertThat(ConnectionContract.evaluate(uri)).as("the URI itself satisfies the contract").isEmpty();
        assertThatThrownBy(() -> app().run(job(uri, "DRY_RUN")))
                .isInstanceOf(DatastoreContractException.class)
                .hasMessageContaining("environment=uat")
                .hasMessageContaining("profile=MIGRATION_READ")
                .satisfies(e -> assertThat(e.getMessage())
                        .containsAnyOf("DATASTORE_UNAVAILABLE", "DATASTORE_CONFIGURATION_ERROR")
                        .doesNotContain("s3cretPW").doesNotContain("appuser").doesNotContain("uat-wiring.invalid"));
        assertThat(EXIT_CODES).isEmpty();
    }

    @Test
    void the_uat_service_shape_uses_the_runtime_profile() {
        assertThatThrownBy(() -> app().run(job("mongodb://localhost:27017/tazzzo_uat?replicaSet=rs0", "VERIFY")))
                .isInstanceOf(DatastoreContractException.class)
                .hasMessageContaining("environment=uat")
                .hasMessageContaining("profile=RUNTIME");
    }
}
