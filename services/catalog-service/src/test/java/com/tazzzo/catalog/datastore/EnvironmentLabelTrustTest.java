package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.datastore.DatastoreStartupVerifier.Enforcement;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.migration.MigrationTarget;
import com.tazzzo.catalog.migration.TargetGuard;
import com.tazzzo.catalog.migration.TargetRefusedException;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The environment label is metadata, not a security boundary (DB-4 hardening, M2): the relaxed local/test/dev treatment
 * is granted only to a demonstrably local datastore, whatever the label says, and DB-3's TargetGuard agrees.
 */
class EnvironmentLabelTrustTest {

    static final String ATLAS_LIKE = "mongodb+srv://appuser:s3cretPW@cluster0.abcde.mongodb.net/tazzzo?retryWrites=true&w=majority";
    static final String REMOTE_HOST = "mongodb://appuser:s3cretPW@db.corp.example.net:27017/tazzzo?replicaSet=rs0";
    static final String REMOTE_IP = "mongodb://appuser:s3cretPW@203.0.113.25:27017/tazzzo?replicaSet=rs0";
    static final String LOCALHOST = "mongodb://localhost:27017/tazzzo?replicaSet=rs0";

    private static DatastoreStartupVerifier.Result verify(String env, String uri) {
        MigrationProperties m = new MigrationProperties();
        m.setEnvironment(env);
        m.setMode(MigrationMode.VERIFY);
        return new DatastoreStartupVerifier(null, "tazzzo_staging", m, new DatastoreProperties(), uri,
                SchemaBootstrap.COLLECTIONS).verify();
    }

    @ParameterizedTest(name = "{0} + remote target -> ENFORCED")
    @ValueSource(strings = {"dev", "test", "local"})
    void a_remote_target_is_enforced_whatever_the_self_serve_label(String env) {
        for (String uri : new String[]{ATLAS_LIKE, REMOTE_HOST, REMOTE_IP,
                "mongodb://10.0.4.7:27017/tazzzo?replicaSet=rs0", "mongodb://192.168.1.20:27017/tazzzo",
                "mongodb://172.17.0.2:27017/tazzzo", "mongodb://db.internal:27017/tazzzo"}) {
            DatastoreStartupVerifier.Result r = verify(env, uri);
            assertThat(r.enforcement()).as(env + " " + uri).isEqualTo(Enforcement.ENFORCED);
            assertThat(r.violations()).as(env + " " + uri).isNotEmpty(); // none of these states the contract
        }
    }

    @Test
    void a_remote_target_under_a_self_serve_label_that_meets_the_contract_is_enforced_and_accepted_by_the_contract() {
        String compliant = ConnectionContractTest.SRV;
        assertThat(verify("dev", compliant).enforcement()).isEqualTo(Enforcement.ENFORCED);
        assertThat(ConnectionContract.evaluate(compliant)).isEmpty();
    }

    @ParameterizedTest(name = "{0} + loopback target -> ADVISORY")
    @ValueSource(strings = {"dev", "test", "local"})
    void a_loopback_target_keeps_the_local_convenience(String env) {
        for (String uri : new String[]{LOCALHOST, "mongodb://127.0.0.1:27017/t", "mongodb://[::1]:27017/t", "mongodb://localhost./t"}) {
            assertThat(verify(env, uri).enforcement()).as(env + " " + uri).isEqualTo(Enforcement.ADVISORY);
        }
    }

    @Test
    void a_target_mixing_a_loopback_and_a_remote_host_is_not_local() {
        assertThat(verify("dev", "mongodb://localhost:27017,db.corp.example.net:27017/t?replicaSet=rs0").enforcement())
                .isEqualTo(Enforcement.ENFORCED);
    }

    @Test
    void an_unparseable_target_under_a_self_serve_label_is_enforced_and_refused() {
        DatastoreStartupVerifier.Result r = verify("dev", "mongodb://u:pw@host:notaport/t");
        assertThat(r.enforcement()).isEqualTo(Enforcement.ENFORCED);
        assertThat(r.violations()).extracting(Violation::code).contains("URI_INVALID");
    }

    @Test
    void the_refusal_happens_before_any_connection_for_remote_dev_test_and_local() {
        for (String env : new String[]{"dev", "test", "local"}) {
            MigrationProperties m = new MigrationProperties();
            m.setEnvironment(env);
            m.setMode(MigrationMode.VERIFY);
            // client is null: any attempt to connect would be a NullPointerException, not a contract refusal
            DatastoreStartupVerifier v = new DatastoreStartupVerifier(null, "tazzzo_staging", m, new DatastoreProperties(),
                    REMOTE_HOST, SchemaBootstrap.COLLECTIONS);
            assertThatThrownBy(() -> v.run(new DefaultApplicationArguments())).as(env)
                    .isInstanceOf(DatastoreContractException.class).hasMessageContaining("TLS_REQUIRED");
        }
    }

    // ---- loopback normalisation ----

    @ParameterizedTest(name = "{0} is loopback")
    @ValueSource(strings = {"localhost", "LOCALHOST", "localhost.", "localhost:27017", "localhost.:27017", "127.0.0.1", "127.0.0.1:27017",
            "127.1.2.3", "127.255.255.254", "2130706433", "2147483647", "[::1]", "[::1]:27017", "[0:0:0:0:0:0:0:1]",
            "[::ffff:127.0.0.1]:27017", "[::ffff:7f00:1]", "[0000::1]", "::1"})
    void provable_loopback_spellings_are_recognised_without_dns(String host) {
        assertThat(ConnectionContract.isLoopback(host)).isTrue();
    }

    @ParameterizedTest(name = "{0} is NOT loopback")
    @ValueSource(strings = {"db.example.net", "db.example.net:27017", "127.evil.example.net", "127.0.0.1.example.net", "127.example.net:27017",
            "10.0.0.1", "192.168.0.10", "172.16.0.5", "172.17.0.2", "169.254.1.1", "8.8.8.8", "203.0.113.25", "1.2.3.4:27017",
            "foo.localhost", "localhost.example.net", "localhost.evil.example.net", "notlocalhost", "128.0.0.1", "126.255.255.255",
            "2147483648", "4294967297", "4294967296", "[2001:db8::1]", "[fe80::1]", "[::ffff:10.0.0.1]", "[::2]", "0x80000001",
            "0377.0.0.1", "256.0.0.1", "", "   ",
            // ambiguous or non-standard spellings: the JDK reads 0177.0.0.1 as 177.0.0.1, so none of these may relax anything
            "0177.0.0.1", "00177.0.0.1", "0177.0.0.01", "127.0.0.01", "127.00.0.1", "0x7f000001", "0x7F.1", "0x7f.0.0.1", "0177.1", "017700000001",
            "127.1", "127.0.1", "127", "0.0.0.0", "0", "[::]", "[::ffff:0.0.0.0]", "[::1%lo0]", "::1%lo0", "127.0.0.1.", "127.0.0.1..",
            "１２７.0.0.1", "+127.0.0.1", "-127.0.0.1", "127.0.0.1 ", "127.0.0.1:99999999", "localhost:abc", "[::1]x", "[::1", "::127.0.0.1",
            "[::ffff:0177.0.0.1]"})
    void everything_else_is_never_assumed_local_even_if_it_looks_internal(String host) {
        if (host.equals("127.0.0.1 ")) { // surrounding whitespace is trimmed, so this IS loopback
            assertThat(ConnectionContract.isLoopback(host)).isTrue();
            return;
        }
        assertThat(ConnectionContract.isLoopback(host)).as(host).isFalse();
    }

    @Test
    void a_null_host_is_not_loopback() {
        assertThat(ConnectionContract.isLoopback(null)).isFalse();
    }

    @Test
    void srv_blank_and_garbage_are_not_local_targets() {
        assertThat(ConnectionContract.isLocalTarget("mongodb+srv://u:p@localhost/t")).isFalse();
        assertThat(ConnectionContract.isLocalTarget("")).isFalse();
        assertThat(ConnectionContract.isLocalTarget(null)).isFalse();
        assertThat(ConnectionContract.isLocalTarget("not a uri")).isFalse();
        assertThat(ConnectionContract.isLocalTarget(LOCALHOST)).isTrue();
    }

    // ---- DB-3 TargetGuard ----

    private static MigrationTarget target(String env, String... hosts) {
        return new MigrationTarget(env, "tazzzo", List.of(hosts), "op", "b");
    }

    @ParameterizedTest(name = "{0} + remote host + {1} is refused")
    @CsvSource({"dev,APPLY_ON_STARTUP", "test,APPLY_ON_STARTUP", "local,APPLY_ON_STARTUP",
            "dev,LEGACY", "test,LEGACY", "local,LEGACY"})
    void a_remote_datastore_never_gains_startup_or_legacy_mutation_from_a_self_serve_label(String env, MigrationMode mode) {
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(mode, target(env, "db.corp.example.net:27017"), null, null))
                .isInstanceOf(TargetRefusedException.class).hasMessageContaining("non-loopback");
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(mode, target(env, "203.0.113.25:27017"), null, null))
                .isInstanceOf(TargetRefusedException.class);
    }

    @ParameterizedTest(name = "{0} + remote host + APPLY needs the two-key confirmation")
    @ValueSource(strings = {"dev", "test", "local"})
    void apply_on_a_remote_datastore_needs_the_same_confirmation_as_staging(String env) {
        MigrationTarget remote = target(env, "cluster0-shard-00-00.abcde.mongodb.net:27017");
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY, remote, null, null))
                .isInstanceOf(TargetRefusedException.class).hasMessageContaining("two-key confirmation");
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY, remote, "tazzzo", "staging"))
                .isInstanceOf(TargetRefusedException.class);
        assertThatCode(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY, remote, "tazzzo", env)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} + loopback keeps the local policy")
    @ValueSource(strings = {"dev", "test", "local"})
    void a_loopback_target_under_a_self_serve_label_is_unchanged(String env) {
        for (String host : new String[]{"localhost:27017", "127.0.0.1:27017", "[::1]:27017", "localhost.:27017"}) {
            for (MigrationMode mode : new MigrationMode[]{MigrationMode.APPLY_ON_STARTUP, MigrationMode.LEGACY, MigrationMode.APPLY}) {
                assertThatCode(() -> TargetGuard.requireMutationAllowed(mode, target(env, host), null, null)).as(env + host + mode)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void an_unknown_or_empty_host_list_fails_closed() {
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY_ON_STARTUP, target("dev"), null, null))
                .isInstanceOf(TargetRefusedException.class);
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY_ON_STARTUP,
                target("dev", "localhost:27017", "db.corp.example.net:27017"), null, null)).isInstanceOf(TargetRefusedException.class);
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY_ON_STARTUP,
                target("dev", "127.evil.example.net:27017"), null, null)).isInstanceOf(TargetRefusedException.class);
    }
}
