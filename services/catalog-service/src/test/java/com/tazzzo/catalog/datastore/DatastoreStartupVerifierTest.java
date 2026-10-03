package com.tazzzo.catalog.datastore;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoConfigurationException;
import com.mongodb.MongoCredential;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.tazzzo.catalog.datastore.DatastoreStartupVerifier.Enforcement;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enforcement decisions and failure classification, without a database: when the connection string fails the contract the
 * verifier must never touch the client (here {@code null}, so any attempt would be a NullPointerException).
 */
class DatastoreStartupVerifierTest {

    static final String DEFAULT_URI = "mongodb://localhost:27017/tazzzo?replicaSet=rs0";
    static final String REMOTE_BAD = "mongodb://appuser:s3cretPW@h1.db.example.net:27017/tazzzo_staging?replicaSet=rs0";

    private static DatastoreStartupVerifier verifier(String env, String uri, DatastoreProperties.Verification v, MigrationMode mode) {
        MigrationProperties m = new MigrationProperties();
        m.setEnvironment(env);
        m.setMode(mode);
        DatastoreProperties p = new DatastoreProperties();
        p.setPrivilegeVerification(v);
        return new DatastoreStartupVerifier(null, "tazzzo_staging", m, p, uri, SchemaBootstrap.COLLECTIONS);
    }

    private static DatastoreStartupVerifier auto(String env, String uri) {
        return verifier(env, uri, DatastoreProperties.Verification.AUTO, MigrationMode.VERIFY);
    }

    @Test
    void local_test_and_dev_are_advisory_and_never_touch_the_database_even_with_the_default_uri() {
        for (String env : new String[]{"local", "test", "dev"}) {
            DatastoreStartupVerifier.Result r = auto(env, DEFAULT_URI).verify();
            assertThat(r.enforcement()).as(env).isEqualTo(Enforcement.ADVISORY);
            assertThat(r.violations()).as(env).isNotEmpty();
            assertThatCode(() -> auto(env, DEFAULT_URI).run(new DefaultApplicationArguments())).as(env).doesNotThrowAnyException();
        }
    }

    @Test
    void an_unset_environment_with_a_loopback_uri_stays_advisory_for_local_convenience() {
        assertThat(auto("", DEFAULT_URI).verify().enforcement()).isEqualTo(Enforcement.ADVISORY);
        assertThat(auto(null, DEFAULT_URI).verify().enforcement()).isEqualTo(Enforcement.ADVISORY);
    }

    @Test
    void an_unset_environment_with_a_remote_uri_is_refused_because_the_environment_cannot_be_trusted() {
        DatastoreStartupVerifier.Result r = auto("", REMOTE_BAD).verify();
        assertThat(r.enforcement()).isEqualTo(Enforcement.ENFORCED);
        assertThat(r.violations()).extracting(Violation::code).contains("ENVIRONMENT_NOT_IDENTIFIED");
        assertThatThrownBy(() -> auto("", REMOTE_BAD).run(new DefaultApplicationArguments())).isInstanceOf(DatastoreContractException.class);
    }

    @Test
    void an_unknown_environment_name_is_refused() {
        DatastoreStartupVerifier.Result r = auto("prod", REMOTE_BAD).verify();
        assertThat(r.enforcement()).isEqualTo(Enforcement.ENFORCED);
        assertThat(r.violations()).extracting(Violation::code).contains("ENVIRONMENT_UNKNOWN");
    }

    @Test
    void staging_and_production_refuse_a_non_compliant_uri_without_touching_the_database() {
        for (String env : new String[]{"staging", "production"}) {
            DatastoreStartupVerifier v = auto(env, DEFAULT_URI);
            assertThat(v.verify().enforcement()).isEqualTo(Enforcement.ENFORCED);
            assertThatThrownBy(() -> v.run(new DefaultApplicationArguments()))
                    .isInstanceOf(DatastoreContractException.class)
                    .hasMessageContaining("refusing to start")
                    .hasMessageContaining("LOOPBACK_HOST_FORBIDDEN")
                    .hasMessageContaining("CREDENTIALS_REQUIRED");
        }
    }

    @Test
    void the_refusal_message_never_contains_the_uri_host_user_or_password() {
        assertThatThrownBy(() -> auto("staging", REMOTE_BAD).run(new DefaultApplicationArguments()))
                .isInstanceOf(DatastoreContractException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("s3cretPW").doesNotContain("appuser")
                        .doesNotContain("example.net").doesNotContain("mongodb://"));
    }

    @Test
    void a_missing_uri_in_production_is_refused() {
        assertThatThrownBy(() -> auto("production", "").run(new DefaultApplicationArguments()))
                .isInstanceOf(DatastoreContractException.class).hasMessageContaining("URI_MISSING");
    }

    @Test
    void enforce_applies_the_contract_in_any_environment_and_there_is_no_way_to_switch_it_off_for_staging() {
        DatastoreStartupVerifier devEnforced = verifier("dev", DEFAULT_URI, DatastoreProperties.Verification.ENFORCE, MigrationMode.VERIFY);
        assertThat(devEnforced.verify().enforcement()).isEqualTo(Enforcement.ENFORCED);
        assertThat(DatastoreProperties.Verification.values()).containsExactly(DatastoreProperties.Verification.AUTO, DatastoreProperties.Verification.ENFORCE);
    }

    @Test
    void the_privilege_profile_follows_the_migration_mode() {
        assertThat(DatastoreStartupVerifier.profileFor(MigrationMode.VERIFY)).isEqualTo(PrivilegeProfile.RUNTIME);
        assertThat(DatastoreStartupVerifier.profileFor(MigrationMode.DRY_RUN)).isEqualTo(PrivilegeProfile.MIGRATION_READ);
        assertThat(DatastoreStartupVerifier.profileFor(MigrationMode.APPLY)).isEqualTo(PrivilegeProfile.MIGRATION_APPLY);
        assertThat(DatastoreStartupVerifier.profileFor(MigrationMode.APPLY_ON_STARTUP)).isEqualTo(PrivilegeProfile.MIGRATION_APPLY);
        assertThat(DatastoreStartupVerifier.profileFor(MigrationMode.LEGACY)).isEqualTo(PrivilegeProfile.MIGRATION_APPLY);
    }

    @Test
    void driver_failures_are_classified_without_echoing_driver_text() {
        ServerAddress addr = new ServerAddress("db.secret-host.example.net", 27017);
        MongoSecurityException auth = new MongoSecurityException(
                MongoCredential.createCredential("secretUser", "admin", "secretPassword".toCharArray()), "bad credentials");
        Violation a = DatastoreStartupVerifier.classify(new RuntimeException("wrapper", auth), "hello");
        assertThat(a.code()).isEqualTo("AUTHENTICATION_FAILED");

        BsonDocument code18 = new BsonDocument("code", new BsonInt32(18)).append("errmsg", new BsonString("Authentication failed for secretUser"));
        assertThat(DatastoreStartupVerifier.classify(new MongoCommandException(code18, addr), "hello").code()).isEqualTo("AUTHENTICATION_FAILED");

        BsonDocument code13 = new BsonDocument("code", new BsonInt32(13)).append("errmsg", new BsonString("not authorized on admin"));
        Violation unauthorized = DatastoreStartupVerifier.classify(new MongoCommandException(code13, addr), "connectionStatus");
        assertThat(unauthorized.code()).isEqualTo("UNAUTHORIZED_COMMAND");
        assertThat(unauthorized.message()).contains("connectionStatus");

        assertThat(DatastoreStartupVerifier.classify(new MongoTimeoutException("Timed out after 1000 ms while waiting for a server"), "hello").code())
                .isEqualTo("DATASTORE_UNAVAILABLE");
        assertThat(DatastoreStartupVerifier.classify(new MongoConfigurationException("Unable to look up SRV record for host secret.example.net"), "hello").code())
                .isEqualTo("DATASTORE_CONFIGURATION_ERROR");
        assertThat(DatastoreStartupVerifier.classify(new IllegalStateException("boom secretPassword"), "hello").code())
                .isEqualTo("DATASTORE_CHECK_FAILED");

        for (Violation v : new Violation[]{a, unauthorized,
                DatastoreStartupVerifier.classify(new MongoConfigurationException("Unable to look up SRV record for host secret.example.net"), "hello"),
                DatastoreStartupVerifier.classify(new IllegalStateException("boom secretPassword"), "hello")}) {
            assertThat(v.toString()).doesNotContain("secretUser").doesNotContain("secretPassword").doesNotContain("secret-host")
                    .doesNotContain("secret.example.net");
        }
    }
}
