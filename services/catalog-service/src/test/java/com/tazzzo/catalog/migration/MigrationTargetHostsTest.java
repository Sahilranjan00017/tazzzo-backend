package com.tazzzo.catalog.migration;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterConnectionMode;
import com.mongodb.connection.ClusterSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Host reporting for the migration target. Plain unit tests: no database, no network, no Spring.
 *
 * <p>Defect fixed here: for a {@code mongodb+srv://} URI the driver keeps the DNS name in {@code srvHost} and leaves {@code hosts}
 * at its {@code 127.0.0.1:27017} placeholder, so an Atlas run was reported as localhost. Since DB-4 the host list also feeds the
 * guard's one input, {@link MigrationTarget#local()}, whenever the target is built without a connection string (the 5-argument
 * constructor and the blank-URI fallback of {@link MigrationTarget#forUri}); there the placeholder classified an Atlas SRV
 * target as LOOPBACK, which under a self-serve label (local/test/dev) skips the two-key APPLY confirmation. With a connection
 * string the classification comes from the string and was already correct. These tests pin both the reporting and that
 * the classification rules themselves are unchanged.
 */
class MigrationTargetHostsTest {

    private static final String SRV = "cluster0.example.mongodb.net";
    private static final String SRV_URI = "mongodb+srv://u:p@" + SRV + "/db?retryWrites=true&w=majority";
    private static final String PLACEHOLDER = "127.0.0.1:27017";

    private static ClusterSettings standard(String... hostPorts) {
        List<ServerAddress> hosts = new ArrayList<>();
        for (String hp : hostPorts) {
            String[] p = hp.split(":");
            hosts.add(new ServerAddress(p[0], Integer.parseInt(p[1])));
        }
        return ClusterSettings.builder().hosts(hosts).build();
    }

    private static ClusterSettings srv(String name) {
        return ClusterSettings.builder().srvHost(name).mode(ClusterConnectionMode.MULTIPLE).build();
    }

    private static List<String> reported(ClusterSettings s) {
        return MigrationStartupRunner.targetHosts(s);
    }

    // ---- standard mongodb:// targets: unchanged behaviour -------------------------------------------------------------

    @Test
    void a_standard_single_host_is_reported_as_host_and_port() {
        assertThat(reported(standard("db.example.net:27017"))).containsExactly("db.example.net:27017");
    }

    @Test
    void a_standard_replica_set_reports_every_host_in_order() {
        assertThat(reported(standard("a.example.net:27017", "b.example.net:27018", "c.example.net:27019")))
                .containsExactly("a.example.net:27017", "b.example.net:27018", "c.example.net:27019");
    }

    @Test
    void a_genuinely_local_standard_target_still_reports_localhost_and_is_still_local() {
        List<String> hosts = reported(standard(PLACEHOLDER));
        assertThat(hosts).containsExactly(PLACEHOLDER);
        assertThat(new MigrationTarget("local", "db", hosts, "op", "b").local()).isTrue();
    }

    // ---- mongodb+srv:// targets ---------------------------------------------------------------------------------------

    @Test
    void an_srv_target_reports_the_srv_hostname() {
        assertThat(reported(srv(SRV))).containsExactly(SRV);
    }

    @Test
    void an_srv_target_never_reports_the_localhost_placeholder() {
        ClusterSettings settings = srv(SRV);
        // precondition that makes this a real regression test: the driver DOES expose the placeholder through getHosts()
        assertThat(settings.getHosts().stream().map(ServerAddress::toString)).containsExactly(PLACEHOLDER);
        assertThat(reported(settings)).doesNotContain(PLACEHOLDER).doesNotContain("localhost:27017");
    }

    @Test
    void the_reported_target_line_carries_the_srv_name_and_no_placeholder_and_no_credentials() {
        MigrationTarget t = MigrationTarget.forUri("staging", "tazzzo_staging", reported(srv(SRV)), SRV_URI, "op", "b");
        assertThat(t.describe()).contains("hosts=[" + SRV + "]").doesNotContain("127.0.0.1").doesNotContain("@").doesNotContain("password");
    }

    // ---- the local-target classification (the guard's only input) --------------------------------------------------------

    @Test
    void an_srv_target_built_from_its_reported_hosts_is_not_local() {
        assertThat(new MigrationTarget("dev", "db", reported(srv(SRV)), "op", "b").local()).isFalse();
        assertThat(MigrationTarget.forUri("dev", "db", reported(srv(SRV)), "", "op", "b").local()).isFalse();
        assertThat(MigrationTarget.forUri("dev", "db", reported(srv(SRV)), null, "op", "b").local()).isFalse();
    }

    @Test
    void the_connection_string_still_decides_when_present_whatever_hosts_are_reported() {
        // the URI wins: an SRV Atlas string is remote even if the host list carried the loopback placeholder, and a loopback string is local
        assertThat(MigrationTarget.forUri("dev", "db", List.of(PLACEHOLDER), SRV_URI, "op", "b").local()).isFalse();
        assertThat(MigrationTarget.forUri("dev", "db", reported(srv(SRV)), SRV_URI, "op", "b").local()).isFalse();
        assertThat(MigrationTarget.forUri("dev", "db", List.of("db.example.net:27017"), "mongodb://127.0.0.1:27017/db", "op", "b").local()).isTrue();
        // a proxy makes the target unclassifiable from the host list, so it is never local
        assertThat(MigrationTarget.forUri("dev", "db", List.of(PLACEHOLDER), "mongodb://127.0.0.1:27017/db?proxyHost=gw.example.net&proxyPort=1080", "op", "b").local()).isFalse();
    }

    @Test
    void an_empty_or_unknown_host_list_stays_fail_closed() {
        assertThat(new MigrationTarget("dev", "db", List.of(), "op", "b").local()).isFalse();
        assertThat(new MigrationTarget("dev", "db", null, "op", "b").local()).isFalse();
    }

    // ---- TargetGuard: unchanged rules, exercised through the classification --------------------------------------------

    /** The guard's whole decision (accepted, or the exact refusal message) for one input. */
    private static String decision(MigrationMode mode, MigrationTarget target, String confirmDb, String confirmEnv) {
        try {
            TargetGuard.requireMutationAllowed(mode, target, confirmDb, confirmEnv);
            return "ALLOWED";
        } catch (TargetRefusedException e) {
            return "REFUSED: " + e.getMessage();
        }
    }

    private static MigrationTarget srvTarget(String env) {
        return MigrationTarget.forUri(env, "tazzzo_staging", reported(srv(SRV)), SRV_URI, "op", "b");
    }

    @Test
    void the_guard_rules_are_unchanged_for_an_srv_staging_target() {
        MigrationTarget staging = srvTarget("staging");
        assertThat(decision(MigrationMode.APPLY_ON_STARTUP, staging, null, null)).startsWith("REFUSED").contains("startup mutation");
        assertThat(decision(MigrationMode.LEGACY, staging, null, null)).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, staging, null, null)).startsWith("REFUSED").contains("two-key confirmation");
        assertThat(decision(MigrationMode.APPLY, staging, "tazzzo_staging", null)).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, staging, "tazzzo_prod", "staging")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, staging, "tazzzo_staging", "staging")).isEqualTo("ALLOWED");
        assertThat(decision(MigrationMode.APPLY, srvTarget("production"), "tazzzo_staging", "staging")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, srvTarget(""), null, null)).startsWith("REFUSED").contains("not identified");
    }

    @Test
    void a_remote_srv_target_under_a_self_serve_label_still_needs_the_two_key_confirmation() {
        for (String env : List.of("local", "test", "dev")) {
            MigrationTarget t = srvTarget(env);
            assertThat(decision(MigrationMode.APPLY, t, null, null)).as(env).startsWith("REFUSED").contains("two-key confirmation");
            assertThat(decision(MigrationMode.APPLY_ON_STARTUP, t, null, null)).as(env).startsWith("REFUSED");
            assertThat(decision(MigrationMode.APPLY, t, "tazzzo_staging", env)).as(env).isEqualTo("ALLOWED");
        }
    }

    @Test
    void the_danger_this_fix_removes_a_placeholder_host_list_makes_a_remote_target_look_self_serve() {
        // characterization of the 5-argument constructor: with the OLD host list the SRV target was classified loopback,
        // so APPLY under a self-serve label needed no confirmation. With the reported SRV name it does.
        MigrationTarget misreported = new MigrationTarget("dev", "tazzzo_staging", List.of(PLACEHOLDER), "op", "b");
        MigrationTarget corrected = new MigrationTarget("dev", "tazzzo_staging", reported(srv(SRV)), "op", "b");
        assertThat(decision(MigrationMode.APPLY, misreported, null, null)).isEqualTo("ALLOWED");
        assertThat(decision(MigrationMode.APPLY, corrected, null, null)).startsWith("REFUSED");
    }

    @Test
    void a_genuinely_local_target_keeps_its_self_serve_treatment() {
        MigrationTarget local = MigrationTarget.forUri("local", "tazzzo_dev", reported(standard(PLACEHOLDER)), "mongodb://127.0.0.1:27017/db", "op", "b");
        assertThat(decision(MigrationMode.APPLY, local, null, null)).isEqualTo("ALLOWED");
        assertThat(decision(MigrationMode.APPLY_ON_STARTUP, local, null, null)).isEqualTo("ALLOWED");
    }

    @Test
    void for_non_self_serve_environments_the_reported_hosts_never_change_the_decision() {
        List<List<String>> variants = List.of(List.of(), List.of(PLACEHOLDER), List.of("db.example.net:27017"), List.of(SRV));
        int compared = 0;
        for (MigrationMode mode : MigrationMode.values()) {
            for (String env : List.of("", "prod", "unknown", "staging", "production")) {
                for (String[] confirm : new String[][]{{null, null}, {"tazzzo_staging", "staging"}, {"wrong", env}, {"tazzzo_staging", env}}) {
                    String baseline = null;
                    for (List<String> hosts : variants) {
                        String d = decision(mode, new MigrationTarget(env, "tazzzo_staging", hosts, "op", "b"), confirm[0], confirm[1]);
                        if (baseline == null) baseline = d;
                        assertThat(d).as("mode=%s env='%s' confirm=%s hosts=%s", mode, env, List.of(String.valueOf(confirm[0]), String.valueOf(confirm[1])), hosts).isEqualTo(baseline);
                        compared++;
                    }
                }
            }
        }
        assertThat(compared).isGreaterThan(100);
    }
}
