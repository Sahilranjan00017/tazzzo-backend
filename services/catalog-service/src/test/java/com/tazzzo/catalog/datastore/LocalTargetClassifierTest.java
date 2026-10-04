package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.datastore.DatastoreStartupVerifier.Enforcement;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.migration.MigrationTarget;
import com.tazzzo.catalog.migration.TargetGuard;
import com.tazzzo.catalog.migration.TargetRefusedException;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ONE authoritative local-target decision ({@link ConnectionContract#isLocalTarget}) drives the startup verifier AND DB-3's
 * {@code TargetGuard}, and it is strict: only what the JDK/driver will unambiguously connect to as loopback, with no proxy.
 * Ambiguous legacy IPv4 spellings (the JDK reads {@code 0177.0.0.1} as 177.0.0.1), the wildcard 0.0.0.0, any proxy option,
 * SRV and any mixed host list are NOT local, whatever the environment label says.
 */
class LocalTargetClassifierTest {

    static final String[] SELF_SERVE = {"dev", "test", "local"};

    /** uri -> expected local? (the single matrix every consumer of the decision is checked against) */
    static final String[][] MATRIX = {
            // provably local, no proxy
            {"mongodb://localhost:27017/t", "true"}, {"mongodb://localhost./t", "true"}, {"mongodb://127.0.0.1:27017/t", "true"},
            {"mongodb://127.2.3.4/t", "true"}, {"mongodb://2130706433/t", "true"}, {"mongodb://[::1]:27017/t", "true"},
            {"mongodb://[::ffff:127.0.0.1]:27017/t", "true"}, {"mongodb://localhost:27017,127.0.0.1:27018/t?replicaSet=r", "true"},
            {"mongodb://u:p@localhost:27017/t?directConnection=true", "true"},
            // ambiguous / non-standard spellings
            {"mongodb://0177.0.0.1:27017/t", "false"}, {"mongodb://00177.0.0.1/t", "false"}, {"mongodb://0x7f000001/t", "false"},
            {"mongodb://0x7f.0.0.1/t", "false"}, {"mongodb://127.1/t", "false"}, {"mongodb://127.0.1/t", "false"},
            {"mongodb://0.0.0.0:27017/t", "false"}, {"mongodb://[::]:27017/t", "false"},
            // any proxy option, even behind a loopback host
            {"mongodb://localhost:27017/t?proxyHost=remote.example.net", "false"},
            {"mongodb://127.0.0.1:27017/t?proxyHost=remote.example.net&proxyPort=1080", "false"},
            {"mongodb://[::1]:27017/t?PROXYHOST=remote.example.net", "false"},
            {"mongodb://localhost/t?proxyPort=1080", "false"}, {"mongodb://localhost/t?proxyUsername=u", "false"},
            {"mongodb://localhost/t?proxyPassword=p", "false"}, {"mongodb://localhost/t?proxyHost=", "false"},
            {"mongodb://localhost/t?proxyHost=a.example.net&proxyHost=b.example.net", "false"},
            {"mongodb://localhost/t?proxyHost=r.example.net&proxyPort=1080&proxyUsername=u&proxyPassword=p", "false"},
            // the driver ALSO accepts ';' as an option delimiter and applies what it reads (review finding)
            {"mongodb://localhost:27017/t?w=majority;proxyHost=evil.example.net", "false"},
            {"mongodb://localhost:27017/t?w=majority;proxyHost=evil.example.net;proxyPort=1080", "false"},
            {"mongodb://127.0.0.1:27017/t?w=majority;proxyHost=evil.example.net", "false"},
            {"mongodb://[::1]:27017/t?w=majority;proxyHost=evil.example.net", "false"},
            {"mongodb://localhost:27017/t?retryWrites=true;PROXYHOST=evil.example.net;ProxyPort=1080", "false"},
            {"mongodb://localhost:27017/t?proxyHost=evil.example.net;w=majority", "false"},
            {"mongodb://localhost:27017/t?w=majority&retryReads=true;proxyHost=evil.example.net&maxPoolSize=5", "false"},
            {"mongodb://localhost:27017/t?w=majority;proxyHost=evil.example.net;proxyUsername=u;proxyPassword=p", "false"},
            {"mongodb://localhost:27017/t?w=majority;proxyPort=1080", "false"},
            {"mongodb://localhost:27017/t?w=majority;maxPoolSize=5", "true"},
            {"mongodb://localhost:27017/t?appName=proxyHostLooking;w=majority", "true"},
            // remote, SRV, mixed, malformed
            {"mongodb://db.example.net:27017/t", "false"}, {"mongodb+srv://cluster0.abcde.mongodb.net/t", "false"},
            {"mongodb+srv://localhost/t", "false"}, {"mongodb://10.0.0.5/t", "false"}, {"mongodb://172.16.0.5/t", "false"},
            {"mongodb://192.168.1.2/t", "false"}, {"mongodb://203.0.113.9/t", "false"}, {"mongodb://[2001:db8::1]/t", "false"},
            {"mongodb://127.evil.example.net/t", "false"}, {"mongodb://localhost.evil.example.net/t", "false"},
            {"mongodb://localhost,remote.example.net/t?replicaSet=r", "false"}, {"mongodb://127.0.0.1,10.0.0.5/t?replicaSet=r", "false"},
            {"mongodb://localhost,0177.0.0.1/t?replicaSet=r", "false"}, {"mongodb://localhost:27017,/t", "true"},
            {"mongodb://localhost:abc/t", "false"}, {"not a uri", "false"}, {"", "false"}};

    private static DatastoreStartupVerifier.Result verify(String env, String uri) {
        MigrationProperties m = new MigrationProperties();
        m.setEnvironment(env);
        m.setMode(MigrationMode.VERIFY);
        return new DatastoreStartupVerifier(null, "tazzzo_staging", m, new DatastoreProperties(), uri, SchemaBootstrap.COLLECTIONS).verify();
    }

    private static MigrationTarget target(String env, String uri) {
        List<String> hosts;
        try {
            hosts = new com.mongodb.ConnectionString(uri).getHosts();
        } catch (RuntimeException e) {
            hosts = List.of();
        }
        return MigrationTarget.forUri(env, "tazzzo", hosts, uri, "op", "b");
    }

    @Test
    void the_classifier_matrix_is_exactly_as_designed() {
        for (String[] row : MATRIX) {
            assertThat(ConnectionContract.isLocalTarget(row[0])).as(row[0]).isEqualTo(Boolean.parseBoolean(row[1]));
        }
    }

    @Test
    void the_startup_verifier_relaxes_a_self_serve_label_exactly_when_the_classifier_says_local() {
        for (String env : SELF_SERVE) {
            for (String[] row : MATRIX) {
                if (row[0].isBlank()) {
                    continue; // a blank string is "nothing configured", handled by the advisory default
                }
                Enforcement expected = Boolean.parseBoolean(row[1]) ? Enforcement.ADVISORY : Enforcement.ENFORCED;
                assertThat(verify(env, row[0]).enforcement()).as(env + " " + row[0]).isEqualTo(expected);
            }
        }
    }

    @Test
    void target_guard_uses_the_same_decision_for_every_row_and_every_self_serve_label() {
        for (String env : SELF_SERVE) {
            for (String[] row : MATRIX) {
                boolean local = Boolean.parseBoolean(row[1]);
                MigrationTarget t = target(env, row[0]);
                assertThat(t.local()).as("same classifier: " + row[0]).isEqualTo(ConnectionContract.isLocalTarget(row[0]));
                for (MigrationMode mode : new MigrationMode[]{MigrationMode.APPLY_ON_STARTUP, MigrationMode.LEGACY}) {
                    if (local) {
                        assertThatCode(() -> TargetGuard.requireMutationAllowed(mode, t, null, null)).as(env + row[0] + mode).doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(mode, t, null, null)).as(env + row[0] + mode)
                                .isInstanceOf(TargetRefusedException.class);
                    }
                }
                if (local) {
                    assertThatCode(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY, t, null, null)).as(env + row[0]).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY, t, null, null)).as(env + row[0])
                            .isInstanceOf(TargetRefusedException.class).hasMessageContaining("two-key confirmation");
                    assertThatCode(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY, t, "tazzzo", env)).as(env + row[0] + " confirmed")
                            .doesNotThrowAnyException();
                }
            }
        }
    }

    @Test
    void a_proxy_cannot_be_hidden_in_the_host_list_only_target() {
        // hosts alone (no URI): loopback; the URI adds the proxy and the target becomes non-local
        MigrationTarget hostsOnly = new MigrationTarget("dev", "tazzzo", List.of("localhost:27017"), "op", "b");
        assertThat(hostsOnly.local()).isTrue();
        MigrationTarget proxied = MigrationTarget.forUri("dev", "tazzzo", List.of("localhost:27017"),
                "mongodb://localhost:27017/t?proxyHost=remote.example.net", "op", "b");
        assertThat(proxied.local()).isFalse();
        assertThatThrownBy(() -> TargetGuard.requireMutationAllowed(MigrationMode.APPLY_ON_STARTUP, proxied, null, null))
                .isInstanceOf(TargetRefusedException.class);
    }

    @Test
    void an_unset_environment_with_an_ambiguous_or_proxied_loopback_is_enforced() {
        for (String uri : new String[]{"mongodb://0177.0.0.1/t", "mongodb://localhost/t?proxyHost=r.example.net", "mongodb://0.0.0.0/t"}) {
            DatastoreStartupVerifier.Result r = verify("", uri);
            assertThat(r.enforcement()).as(uri).isEqualTo(Enforcement.ENFORCED);
            assertThat(r.violations()).extracting(Violation::code).as(uri).contains("ENVIRONMENT_NOT_IDENTIFIED");
        }
    }

    @Test
    void every_proxy_option_is_detected_on_its_own_independent_of_the_drivers_own_validation() {
        // the driver rejects proxyPort/proxyUsername/proxyPassword without proxyHost (so the URI is simply invalid and not local);
        // the detector must not depend on that: each name, in any case, counts as a proxy on its own
        for (String name : new String[]{"proxyHost", "proxyPort", "proxyUsername", "proxyPassword", "PROXYHOST", "ProxyPort", "proxyusername", "PROXYPASSWORD"}) {
            assertThat(ConnectionContract.hasProxyOption("mongodb://localhost/t?" + name + "=x")).as(name).isTrue();
            assertThat(ConnectionContract.hasProxyOption("mongodb://localhost/t?w=majority&" + name + "=x&retryWrites=true")).as(name + " among others").isTrue();
        }
        // both delimiters the driver accepts, mixed, in any case
        for (String uri : new String[]{"mongodb://localhost/t?w=majority;proxyHost=x", "mongodb://localhost/t?w=majority;proxyHost=x;proxyPort=1080",
                "mongodb://localhost/t?a=1&w=majority;PROXYHOST=x&retryReads=true", "mongodb://localhost/t?proxyHost=x;w=majority"}) {
            assertThat(ConnectionContract.hasProxyOption(uri)).as(uri).isTrue();
        }
        assertThat(ConnectionContract.hasProxyOption("mongodb://localhost/t")).isFalse();
        assertThat(ConnectionContract.hasProxyOption("mongodb://localhost/t?appName=proxyHostLooking&w=majority")).isFalse();
    }

    // ---- the classifier agrees with what the JDK actually connects to ----

    @Test
    void a_numeric_host_classified_loopback_is_read_by_the_jdk_as_loopback_and_an_ambiguous_one_never_is() throws Exception {
        for (String h : new String[]{"127.0.0.1", "127.9.8.7", "2130706433", "2147483647"}) {
            assertThat(ConnectionContract.isLoopback(h)).as(h).isTrue();
            assertThat(InetAddress.getByName(h).isLoopbackAddress()).as("JDK reads " + h + " as loopback").isTrue();
        }
        // the root cause of the finding: the JDK reads a leading-zero quad as DECIMAL, so it is 177.0.0.1, not 127.0.0.1
        for (String h : new String[]{"0177.0.0.1", "00177.0.0.1", "0x7f000001", "0x7f.1", "127.1", "127.0.1", "0.0.0.0", "127.0.0.01"}) {
            assertThat(ConnectionContract.isLoopback(h)).as(h).isFalse();
        }
        assertThat(Arrays.toString(InetAddress.getByName("0177.0.0.1").getAddress())).as("JDK interpretation of the octal-looking host")
                .isEqualTo(Arrays.toString(new byte[]{(byte) 177, 0, 0, 1}));
    }

    @Test
    void the_broad_predicate_still_forbids_every_possible_loopback_spelling_in_staging_and_production() {
        // fail closed in the OTHER direction: the contract's "no loopback host" rule stays over-inclusive
        for (String host : new String[]{"127.0.0.1", "127.1", "0177.0.0.1", "0x7f000001", "0.0.0.0", "2130706433", "localhost", "localhost.", "[::1]"}) {
            String uri = "mongodb://appuser:s3cretPW@" + host + ":27017/tazzzo_staging?replicaSet=rs0&tls=true&" + ConnectionContractTest.OPTIONS;
            assertThat(ConnectionContract.evaluate(uri)).extracting(Violation::code).as(host).contains("LOOPBACK_HOST_FORBIDDEN");
        }
        assertThat(ConnectionContract.evaluate(ConnectionContractTest.RS)).isEmpty();
    }

    @Test
    void no_dns_lookup_is_ever_triggered_by_classification() {
        // names that would be looked up if they reached the JDK resolver are decided textually and never resolved
        long start = System.nanoTime();
        for (String h : new String[]{"db.invalid", "localhost.invalid", "[dead:beef.invalid]", "1.2.3.4.5", "999.999.999.999", "4294967297"}) {
            assertThat(ConnectionContract.isLoopback(h)).as(h).isFalse();
        }
        assertThat((System.nanoTime() - start) / 1_000_000L).as("ms: no resolver round trip").isLessThan(2_000);
    }
}
