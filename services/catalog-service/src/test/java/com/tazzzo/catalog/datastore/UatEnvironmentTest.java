package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.datastore.DatastoreStartupVerifier.Enforcement;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.migration.MigrationTarget;
import com.tazzzo.catalog.migration.TargetGuard;
import com.tazzzo.catalog.migration.TargetRefusedException;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code uat} environment (user acceptance, database {@code tazzzo_uat}): recognised, never self-serve, enforced like
 * staging/production by the datastore verifier, and the two-key APPLY confirmation must name {@code uat} itself. Plain unit
 * tests: no database, no network, no Spring. The connection strings are synthetic placeholders.
 */
class UatEnvironmentTest {

    static final String DB = "tazzzo_uat";
    /** An Atlas-shaped SRV target for the UAT database that states every contract option (placeholder credentials). */
    static final String UAT_SRV = "mongodb+srv://uatuser:placeholderPW@cluster0.abcde12.mongodb.net/" + DB + "?"
            + ConnectionContractTest.OPTIONS;
    static final String UAT_LOOPBACK = "mongodb://localhost:27017/" + DB + "?replicaSet=rs0";
    static final String UAT_REMOTE_BAD = "mongodb://uatuser:placeholderPW@db.uat.example.net:27017/" + DB + "?replicaSet=rs0";

    // ---- recognition -------------------------------------------------------------------------------------------------

    @Test
    void uat_is_a_known_environment_but_not_a_self_serve_one() {
        assertThat(TargetGuard.KNOWN_ENVIRONMENTS).contains("uat");
        assertThat(TargetGuard.SELF_SERVE_ENVIRONMENTS).doesNotContain("uat").containsExactlyInAnyOrder("local", "test", "dev");
        // the existing names are all still there
        assertThat(TargetGuard.KNOWN_ENVIRONMENTS).containsExactlyInAnyOrder("local", "test", "dev", "uat", "staging", "production");
    }

    // ---- TargetGuard: remote UAT -------------------------------------------------------------------------------------

    private static String decision(MigrationMode mode, MigrationTarget target, String confirmDb, String confirmEnv) {
        try {
            TargetGuard.requireMutationAllowed(mode, target, confirmDb, confirmEnv);
            return "ALLOWED";
        } catch (TargetRefusedException e) {
            return "REFUSED: " + e.getMessage();
        }
    }

    private static MigrationTarget uat(String uri) {
        return MigrationTarget.forUri("uat", DB, List.of("cluster0.abcde12.mongodb.net"), uri, "op", "b");
    }

    @Test
    void remote_uat_apply_needs_the_exact_database_and_the_exact_environment() {
        MigrationTarget t = uat(UAT_SRV);
        assertThat(t.local()).isFalse();
        assertThat(decision(MigrationMode.APPLY, t, null, null)).startsWith("REFUSED").contains("two-key confirmation");
        assertThat(decision(MigrationMode.APPLY, t, DB, null)).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, t, null, "uat")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, t, "", "")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, t, DB, "uat")).isEqualTo("ALLOWED");
    }

    @Test
    void staging_is_never_silently_accepted_in_place_of_uat() {
        MigrationTarget t = uat(UAT_SRV);
        assertThat(decision(MigrationMode.APPLY, t, DB, "staging")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, t, "tazzzo_staging", "uat")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, t, "tazzzo_staging", "staging")).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, t, DB, "production")).startsWith("REFUSED");
        // and the other way round: a staging target is not unlocked by a uat confirmation
        MigrationTarget staging = MigrationTarget.forUri("staging", "tazzzo_staging", List.of("x.mongodb.net"),
                ConnectionContractTest.SRV, "op", "b");
        assertThat(decision(MigrationMode.APPLY, staging, "tazzzo_staging", "uat")).startsWith("REFUSED");
    }

    @Test
    void the_confirmation_is_exact_not_case_insensitive_or_trimmed() {
        MigrationTarget t = uat(UAT_SRV);
        for (String env : new String[]{"UAT", "Uat", " uat", "uat ", "uat\n"}) {
            assertThat(decision(MigrationMode.APPLY, t, DB, env)).as("[" + env + "]").startsWith("REFUSED");
        }
        for (String db : new String[]{"TAZZZO_UAT", " tazzzo_uat", "tazzzo_uat ", "tazzzo_uat2", "tazzzo"}) {
            assertThat(decision(MigrationMode.APPLY, t, db, "uat")).as("[" + db + "]").startsWith("REFUSED");
        }
    }

    @ParameterizedTest(name = "{0} is refused against remote uat")
    @ValueSource(strings = {"APPLY_ON_STARTUP", "LEGACY"})
    void startup_and_legacy_mutation_are_refused_against_remote_uat_even_with_a_valid_confirmation(String mode) {
        MigrationMode m = MigrationMode.valueOf(mode);
        assertThat(decision(m, uat(UAT_SRV), null, null)).startsWith("REFUSED").contains("startup mutation");
        assertThat(decision(m, uat(UAT_SRV), DB, "uat")).startsWith("REFUSED").contains("startup mutation");
    }

    @Test
    void uat_on_a_loopback_server_is_still_not_self_serve() {
        MigrationTarget local = MigrationTarget.forUri("uat", DB, List.of("localhost:27017"), UAT_LOOPBACK, "op", "b");
        assertThat(local.local()).isTrue();
        assertThat(decision(MigrationMode.APPLY_ON_STARTUP, local, null, null)).startsWith("REFUSED");
        assertThat(decision(MigrationMode.LEGACY, local, null, null)).startsWith("REFUSED");
        assertThat(decision(MigrationMode.APPLY, local, null, null)).startsWith("REFUSED").contains("two-key confirmation");
        assertThat(decision(MigrationMode.APPLY, local, DB, "uat")).isEqualTo("ALLOWED");
    }

    @ParameterizedTest(name = "unknown environment [{0}] is still refused")
    @ValueSource(strings = {"UAT", "uat-prod", "user-acceptance", "qa", "preprod", " uat"})
    void near_miss_environment_names_remain_unknown(String env) {
        MigrationTarget t = MigrationTarget.forUri(env, DB, List.of("x"), UAT_SRV, "op", "b");
        assertThat(decision(MigrationMode.APPLY, t, DB, env)).startsWith("REFUSED").contains("unknown environment");
    }

    @Test
    void a_missing_database_is_refused_for_uat() {
        MigrationTarget t = MigrationTarget.forUri("uat", "", List.of("x"), UAT_SRV, "op", "b");
        assertThat(decision(MigrationMode.APPLY, t, "", "uat")).startsWith("REFUSED").contains("database is not identified");
    }

    // ---- DatastoreStartupVerifier ------------------------------------------------------------------------------------

    private static DatastoreStartupVerifier verifier(String uri, MigrationMode mode) {
        MigrationProperties m = new MigrationProperties();
        m.setEnvironment("uat");
        m.setMode(mode);
        // client is null: any connection attempt surfaces as a classified live failure, never as a contract pass
        return new DatastoreStartupVerifier(null, DB, m, new DatastoreProperties(), uri, SchemaBootstrap.COLLECTIONS);
    }

    @Test
    void a_compliant_uat_atlas_srv_string_satisfies_the_connection_contract() {
        assertThat(ConnectionContract.evaluate(UAT_SRV)).isEmpty();
        assertThat(ConnectionContract.isLocalTarget(UAT_SRV)).isFalse();
    }

    @ParameterizedTest(name = "uat + {0} is enforced, never advisory")
    @ValueSource(strings = {"VERIFY", "DRY_RUN", "APPLY"})
    void uat_is_always_enforced(String mode) {
        for (String uri : new String[]{UAT_SRV, UAT_LOOPBACK, UAT_REMOTE_BAD, ""}) {
            DatastoreStartupVerifier.Result r = verifier(uri, MigrationMode.valueOf(mode)).verify();
            assertThat(r.enforcement()).as(mode + " [" + uri + "]").isEqualTo(Enforcement.ENFORCED);
            assertThat(r.violations()).as(mode + " [" + uri + "]").extracting(Violation::code).doesNotContain("ENVIRONMENT_UNKNOWN");
        }
    }

    @Test
    void uat_on_loopback_is_refused_like_staging_instead_of_getting_the_dev_convenience() {
        DatastoreStartupVerifier.Result r = verifier(UAT_LOOPBACK, MigrationMode.VERIFY).verify();
        assertThat(r.violations()).extracting(Violation::code).contains("LOOPBACK_HOST_FORBIDDEN");
        assertThatThrownBy(() -> verifier(UAT_LOOPBACK, MigrationMode.VERIFY).run(new DefaultApplicationArguments()))
                .isInstanceOf(DatastoreContractException.class);
    }

    @Test
    void a_non_compliant_uat_string_is_refused_before_any_connection() {
        assertThatThrownBy(() -> verifier(UAT_REMOTE_BAD, MigrationMode.DRY_RUN).run(new DefaultApplicationArguments()))
                .isInstanceOf(DatastoreContractException.class).hasMessageContaining("TLS_REQUIRED");
    }

    @Test
    void the_profile_per_mode_is_unchanged_for_uat() {
        assertThat(verifier(UAT_SRV, MigrationMode.VERIFY).verify().profile()).isEqualTo(PrivilegeProfile.RUNTIME);
        assertThat(verifier(UAT_SRV, MigrationMode.DRY_RUN).verify().profile()).isEqualTo(PrivilegeProfile.MIGRATION_READ);
        assertThat(verifier(UAT_SRV, MigrationMode.APPLY).verify().profile()).isEqualTo(PrivilegeProfile.MIGRATION_APPLY);
    }

    // ---- privileges on the UAT database ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Document> privileges(PrivilegeModel.Spec spec, String db, List<String> collections) {
        return (List<Document>) PrivilegeModel.createRole("r", spec, db, collections).get("privileges");
    }

    private static Document status(List<Document> privileges) {
        return new Document("authInfo", new Document("authenticatedUsers", List.of(new Document("user", "u").append("db", "admin")))
                .append("authenticatedUserRoles", List.of())
                .append("authenticatedUserPrivileges", privileges)).append("ok", 1);
    }

    private static List<String> codes(List<Violation> v) {
        return v.stream().map(Violation::code).distinct().toList();
    }

    @Test
    void the_generated_roles_for_tazzzo_uat_pass_their_own_profiles_and_their_sizes_are_pinned() {
        List<String> colls = SchemaBootstrap.COLLECTIONS;
        List<Document> runtime = privileges(PrivilegeModel.Spec.RUNTIME, DB, colls);
        List<Document> migrator = privileges(PrivilegeModel.Spec.MIGRATOR, DB, colls);
        assertThat(PrivilegeInspector.inspect(status(runtime), DB, colls, PrivilegeProfile.RUNTIME)).isEmpty();
        assertThat(PrivilegeInspector.inspect(status(migrator), DB, colls, PrivilegeProfile.MIGRATION_APPLY)).isEmpty();
        assertThat(PrivilegeInspector.inspect(status(migrator), DB, colls, PrivilegeProfile.MIGRATION_READ)).isEmpty();
        // what an operator compares with the Atlas custom roles (privilege groups / action-resource pairs)
        assertThat(runtime).hasSize(colls.size() + 2);
        assertThat(runtime.stream().mapToInt(p -> p.getList("actions", String.class).size()).sum()).isEqualTo(4 * colls.size() + 2);
        assertThat(migrator).hasSize(7);
        assertThat(migrator.stream().mapToInt(p -> p.getList("actions", String.class).size()).sum()).isEqualTo(19);
        assertThat(runtime).allSatisfy(p -> assertThat(p.get("resource", Document.class).getString("db")).isEqualTo(DB));
    }

    @Test
    void a_role_granted_on_the_staging_database_does_not_satisfy_uat() {
        List<Document> stagingGrants = privileges(PrivilegeModel.Spec.MIGRATOR, "tazzzo_staging", SchemaBootstrap.COLLECTIONS);
        List<Violation> v = PrivilegeInspector.inspect(status(stagingGrants), DB, SchemaBootstrap.COLLECTIONS, PrivilegeProfile.MIGRATION_APPLY);
        assertThat(codes(v)).contains("OUT_OF_SCOPE_PRIVILEGE", "MISSING_PRIVILEGE");
    }

    @Test
    void a_runtime_role_built_from_an_older_collection_roster_is_refused_with_the_missing_collections_named() {
        // the DB-4-era roster lacked these eight collections; a runtime role created from that role file fails VERIFY
        Set<String> later = Set.of("content_blocks", "customer_address_idempotency", "delivery_slot_usage", "delivery_slot_windows",
                "import_jobs", "import_rows", "notification_outbox", "support_cases");
        assertThat(SchemaBootstrap.COLLECTIONS).containsAll(later);
        List<String> older = SchemaBootstrap.COLLECTIONS.stream().filter(c -> !later.contains(c)).toList();
        List<Document> stale = privileges(PrivilegeModel.Spec.RUNTIME, DB, older);
        List<Violation> v = PrivilegeInspector.inspect(status(stale), DB, SchemaBootstrap.COLLECTIONS, PrivilegeProfile.RUNTIME);
        assertThat(codes(v)).containsExactly("MISSING_PRIVILEGE");
        assertThat(stale).hasSize(older.size() + 2);
    }
}
