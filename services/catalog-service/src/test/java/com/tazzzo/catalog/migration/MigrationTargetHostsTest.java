package com.tazzzo.catalog.migration;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterConnectionMode;
import com.mongodb.connection.ClusterSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Host reporting for the migration target. Plain unit tests: no database, no network, no Spring.
 *
 * <p>Defect fixed here: for a {@code mongodb+srv://} URI the driver keeps the DNS name in {@code srvHost} and leaves {@code hosts}
 * at its {@code 127.0.0.1:27017} placeholder, so an Atlas run was reported as localhost. The hosts are informational (log line and
 * run report); the guard must not depend on them, which the last tests pin.
 */
class MigrationTargetHostsTest {

    private static final String SRV = "cluster0.example.mongodb.net";

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

    // ---- standard mongodb:// targets: unchanged behaviour -------------------------------------------------------------

    @Test
    void a_standard_single_host_is_reported_as_host_and_port() {
        assertThat(MigrationStartupRunner.targetHosts(standard("db.example.net:27017"))).containsExactly("db.example.net:27017");
    }

    @Test
    void a_standard_replica_set_reports_every_host_in_order() {
        assertThat(MigrationStartupRunner.targetHosts(standard("a.example.net:27017", "b.example.net:27018", "c.example.net:27019")))
                .containsExactly("a.example.net:27017", "b.example.net:27018", "c.example.net:27019");
    }

    @Test
    void a_genuinely_local_standard_target_still_reports_localhost() {
        // the placeholder is only wrong for SRV; a real mongodb://127.0.0.1:27017 target must keep reporting it
        assertThat(MigrationStartupRunner.targetHosts(standard("127.0.0.1:27017"))).containsExactly("127.0.0.1:27017");
    }

    // ---- mongodb+srv:// targets ---------------------------------------------------------------------------------------

    @Test
    void an_srv_target_reports_the_srv_hostname() {
        assertThat(MigrationStartupRunner.targetHosts(srv(SRV))).containsExactly(SRV);
    }

    @Test
    void an_srv_target_never_reports_the_localhost_placeholder() {
        ClusterSettings settings = srv(SRV);
        // precondition that makes this a real regression test: the driver DOES expose the placeholder through getHosts()
        assertThat(settings.getHosts().stream().map(ServerAddress::toString)).containsExactly("127.0.0.1:27017");
        assertThat(MigrationStartupRunner.targetHosts(settings)).doesNotContain("127.0.0.1:27017").doesNotContain("localhost:27017");
    }

    @Test
    void the_reported_target_line_carries_the_srv_name_and_no_placeholder_and_no_credentials() {
        MigrationTarget t = new MigrationTarget("staging", "tazzzo_staging", MigrationStartupRunner.targetHosts(srv(SRV)), "op", "b");
        assertThat(t.describe()).contains("hosts=[" + SRV + "]").doesNotContain("127.0.0.1").doesNotContain("@").doesNotContain("password");
    }

    // ---- the guard is untouched by the host list ------------------------------------------------------------------------

    /** The guard's whole decision (accepted, or the exact refusal message) for one input. */
    private static String decision(MigrationMode mode, MigrationTarget target, String confirmDb, String confirmEnv) {
        try {
            TargetGuard.requireMutationAllowed(mode, target, confirmDb, confirmEnv);
            return "ALLOWED";
        } catch (TargetRefusedException e) {
            return "REFUSED: " + e.getMessage();
        }
    }

    @Test
    void the_guard_decides_identically_whatever_hosts_are_reported() {
        List<List<String>> hostVariants = List.of(List.of(), List.of("127.0.0.1:27017"), List.of("db.example.net:27017"), List.of(SRV));
        List<String> environments = new ArrayList<>(List.of("", "prod", "unknown"));
        environments.addAll(Set.of("local", "test", "dev", "staging", "production"));
        int compared = 0;
        for (MigrationMode mode : MigrationMode.values()) {
            for (String env : environments) {
                for (String[] confirm : new String[][]{{null, null}, {"tazzzo_staging", "staging"}, {"wrong", env}, {"tazzzo_staging", env}}) {
                    String baseline = null;
                    for (List<String> hosts : hostVariants) {
                        MigrationTarget t = new MigrationTarget(env, "tazzzo_staging", hosts, "op", "b");
                        String d = decision(mode, t, confirm[0], confirm[1]);
                        if (baseline == null) baseline = d;
                        assertThat(d).as("mode=%s env='%s' confirm=%s hosts=%s", mode, env, List.of(String.valueOf(confirm[0]), String.valueOf(confirm[1])), hosts)
                                .isEqualTo(baseline);
                        compared++;
                    }
                }
            }
        }
        assertThat(compared).isGreaterThan(100);
    }

    @Test
    void the_guard_rules_are_unchanged_for_an_srv_staging_target() {
        MigrationTarget staging = new MigrationTarget("staging", "tazzzo_staging", List.of(SRV), "op", "b");
        // startup mutation is refused outside local/test/dev
        assertThat(decision(MigrationMode.APPLY_ON_STARTUP, staging, null, null)).startsWith("REFUSED").contains("startup mutation");
        assertThat(decision(MigrationMode.LEGACY, staging, null, null)).startsWith("REFUSED");
        // APPLY needs the two-key confirmation, both keys exact
        assertThat(decision(MigrationMode.APPLY, staging, null, null)).startsWith("REFUSED").contains("two-key confirmation");
        assertThat(decision(MigrationMode.APPLY, staging, "tazzzo_staging", null)).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, staging, "tazzzo_prod", "staging")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, staging, "tazzzo_staging", "staging")).isEqualTo("ALLOWED");
        // production is gated exactly the same way
        MigrationTarget prod = new MigrationTarget("production", "tazzzo_staging", List.of(SRV), "op", "b");
        assertThat(decision(MigrationMode.APPLY, prod, "tazzzo_staging", "staging")).startsWith("REFUSED");
        // an unidentified environment is refused whatever the host
        assertThat(decision(MigrationMode.APPLY, new MigrationTarget("", "tazzzo_staging", List.of(SRV), "op", "b"), null, null)).startsWith("REFUSED").contains("not identified");
    }
}
