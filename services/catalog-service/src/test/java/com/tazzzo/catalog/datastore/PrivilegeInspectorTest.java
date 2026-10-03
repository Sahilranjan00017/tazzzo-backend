package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Privilege inspection against synthetic {@code connectionStatus} results: no database. */
class PrivilegeInspectorTest {

    static final String DB = "tazzzo_staging";
    static final List<String> COLLS = SchemaBootstrap.COLLECTIONS;

    @SuppressWarnings("unchecked")
    private static Document status(String user, List<Document> privileges) {
        List<Document> users = user == null ? List.of() : List.of(new Document("user", user).append("db", DB));
        return new Document("authInfo", new Document("authenticatedUsers", users)
                .append("authenticatedUserRoles", List.of())
                .append("authenticatedUserPrivileges", privileges)).append("ok", 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Document> privilegesOf(PrivilegeModel.Spec spec) {
        return (List<Document>) PrivilegeModel.createRole("r", spec, DB, COLLS).get("privileges");
    }

    private static Document priv(String db, String collection, String... actions) {
        return new Document("resource", new Document("db", db).append("collection", collection)).append("actions", List.of(actions));
    }

    private static List<Violation> inspect(Document status, PrivilegeProfile profile) {
        return PrivilegeInspector.inspect(status, DB, COLLS, profile);
    }

    private static List<String> codes(List<Violation> v) {
        return v.stream().map(Violation::code).distinct().toList();
    }

    private static String text(List<Violation> v) {
        return String.join(" | ", v.stream().map(Violation::toString).toList());
    }

    @Test
    void the_runtime_role_exactly_as_generated_is_accepted_for_the_runtime_profile_only() {
        Document s = status("app", privilegesOf(PrivilegeModel.Spec.RUNTIME));
        assertThat(inspect(s, PrivilegeProfile.RUNTIME)).isEmpty();
        // the runtime identity cannot run a migration or even a dry run: no listIndexes, no DDL
        assertThat(codes(inspect(s, PrivilegeProfile.MIGRATION_APPLY))).contains("MISSING_PRIVILEGE", "EXCESS_PRIVILEGE");
        assertThat(text(inspect(s, PrivilegeProfile.MIGRATION_READ))).contains("listIndexes is required");
    }

    @Test
    void the_migrator_role_exactly_as_generated_is_accepted_for_apply_and_for_dry_run_but_never_as_the_runtime() {
        Document s = status("migrator", privilegesOf(PrivilegeModel.Spec.MIGRATOR));
        assertThat(inspect(s, PrivilegeProfile.MIGRATION_APPLY)).isEmpty();
        assertThat(inspect(s, PrivilegeProfile.MIGRATION_READ)).isEmpty();
        List<Violation> asRuntime = inspect(s, PrivilegeProfile.RUNTIME);
        assertThat(codes(asRuntime)).contains("EXCESS_PRIVILEGE");
        assertThat(text(asRuntime)).contains("createIndex is held", "dropIndex is held", "collMod is held", "createCollection is held");
    }

    @Test
    void a_read_only_identity_may_dry_run_but_cannot_apply() {
        Document s = status("reader", List.of(priv(DB, "", "find", "listCollections", "listIndexes")));
        assertThat(inspect(s, PrivilegeProfile.MIGRATION_READ)).isEmpty();
        String apply = text(inspect(s, PrivilegeProfile.MIGRATION_APPLY));
        assertThat(apply).contains("createIndex is required", "dropIndex is required", "collMod is required", "createCollection is required");
    }

    @Test
    void the_built_in_read_write_role_is_refused_for_the_runtime_because_it_carries_schema_authority() {
        Document s = status("app", List.of(priv(DB, "", "collStats", "convertToCapped", "createCollection", "createIndex", "dbHash",
                "dbStats", "dropCollection", "dropIndex", "find", "insert", "killCursors", "listCollections", "listIndexes",
                "remove", "renameCollectionSameDB", "update")));
        String t = text(inspect(s, PrivilegeProfile.RUNTIME));
        assertThat(t).contains("createIndex is held", "dropIndex is held", "dropCollection is held", "createCollection is held",
                "renameCollectionSameDB is held");
    }

    @Test
    void a_database_wide_write_is_refused_for_the_runtime_because_it_would_cover_the_migration_bookkeeping() {
        List<Document> p = new ArrayList<>(privilegesOf(PrivilegeModel.Spec.RUNTIME));
        p.add(priv(DB, "", "insert", "update", "remove", "find"));
        String t = text(inspect(status("app", p), PrivilegeProfile.RUNTIME));
        assertThat(t).contains("insert is held on", PrivilegeModel.HISTORY, PrivilegeModel.LOCK);
    }

    @Test
    void the_runtime_may_read_but_never_write_the_migration_history_and_never_touch_the_lock() {
        List<Document> p = new ArrayList<>(privilegesOf(PrivilegeModel.Spec.RUNTIME));
        p.add(priv(DB, PrivilegeModel.HISTORY, "insert", "update"));
        p.add(priv(DB, PrivilegeModel.LOCK, "find"));
        String t = text(inspect(status("app", p), PrivilegeProfile.RUNTIME));
        assertThat(t).contains("insert is held on " + PrivilegeModel.HISTORY, "update is held on " + PrivilegeModel.HISTORY,
                "find is held on " + PrivilegeModel.LOCK);
    }

    @Test
    void a_missing_data_privilege_is_reported_by_action_and_collection() {
        List<Document> p = new ArrayList<>(privilegesOf(PrivilegeModel.Spec.RUNTIME));
        p.removeIf(d -> "orders".equals(((Document) d.get("resource")).getString("collection")));
        p.add(priv(DB, "orders", "find", "update", "remove"));
        List<Violation> v = inspect(status("app", p), PrivilegeProfile.RUNTIME);
        assertThat(codes(v)).containsExactly("MISSING_PRIVILEGE");
        assertThat(text(v)).contains("insert is required on orders");
    }

    @Test
    void anything_outside_the_application_database_is_out_of_scope() {
        List<Document> p = new ArrayList<>(privilegesOf(PrivilegeModel.Spec.RUNTIME));
        p.add(priv("other_db", "", "find"));
        p.add(priv("", "", "find"));
        p.add(new Document("resource", new Document("cluster", true)).append("actions", List.of("shutdown")));
        p.add(new Document("resource", new Document("anyResource", true)).append("actions", List.of("anyAction")));
        List<Violation> v = inspect(status("app", p), PrivilegeProfile.RUNTIME);
        assertThat(codes(v)).containsExactly("OUT_OF_SCOPE_PRIVILEGE");
        assertThat(text(v)).contains("database 'other_db'", "all databases", "cluster-wide", "any resource");
    }

    @Test
    void a_collection_the_application_does_not_own_is_excess() {
        List<Document> p = new ArrayList<>(privilegesOf(PrivilegeModel.Spec.RUNTIME));
        p.add(priv(DB, "some_stray_collection", "find"));
        assertThat(text(inspect(status("app", p), PrivilegeProfile.RUNTIME))).contains("find is held on some_stray_collection");
    }

    @Test
    void an_unauthenticated_connection_cannot_be_inspected() {
        List<Violation> v = inspect(status(null, List.of()), PrivilegeProfile.RUNTIME);
        assertThat(codes(v)).containsExactly("NOT_AUTHENTICATED");
        assertThat(inspect(null, PrivilegeProfile.RUNTIME)).extracting(Violation::code).containsExactly("NOT_AUTHENTICATED");
    }

    @Test
    void findings_are_bounded_and_never_contain_the_user_name() {
        Document s = status("verySecretUserName", List.of(priv(DB, "", "createIndex", "dropIndex", "find", "insert", "update", "remove",
                "listCollections", "listIndexes", "createCollection", "collMod", "dropCollection")));
        String t = text(inspect(s, PrivilegeProfile.RUNTIME));
        assertThat(t).doesNotContain("verySecretUserName");
        for (Violation v : inspect(s, PrivilegeProfile.RUNTIME)) {
            assertThat(v.message().length()).as(v.toString()).isLessThan(400);
        }
    }

    @Test
    void the_generated_roles_are_internally_consistent() {
        // required == ceiling for the runtime and for the migrator; the dry-run requirement is a subset of the migrator's
        assertThat(PrivilegeModel.required(PrivilegeProfile.RUNTIME, COLLS)).isEqualTo(PrivilegeModel.ceiling(PrivilegeProfile.RUNTIME, COLLS));
        assertThat(PrivilegeModel.required(PrivilegeProfile.MIGRATION_APPLY, COLLS)).isEqualTo(PrivilegeModel.ceiling(PrivilegeProfile.MIGRATION_APPLY, COLLS));
        assertThat(PrivilegeModel.ceiling(PrivilegeProfile.MIGRATION_APPLY, COLLS)).containsAll(PrivilegeModel.required(PrivilegeProfile.MIGRATION_READ, COLLS));
        // the runtime holds data access on every application collection and on nothing else, and no schema authority at all
        for (String c : COLLS) {
            assertThat(PrivilegeModel.required(PrivilegeProfile.RUNTIME, COLLS))
                    .contains(new PrivilegeModel.Cap(c, "find"), new PrivilegeModel.Cap(c, "insert"),
                            new PrivilegeModel.Cap(c, "update"), new PrivilegeModel.Cap(c, "remove"));
        }
        assertThat(PrivilegeModel.required(PrivilegeProfile.RUNTIME, COLLS)).extracting(PrivilegeModel.Cap::action)
                .doesNotContain("createIndex", "dropIndex", "collMod", "createCollection", "dropCollection", "dropDatabase");
        assertThat(PrivilegeModel.required(PrivilegeProfile.RUNTIME, COLLS)).doesNotContain(
                new PrivilegeModel.Cap(PrivilegeModel.HISTORY, "insert"), new PrivilegeModel.Cap(PrivilegeModel.HISTORY, "update"),
                new PrivilegeModel.Cap(PrivilegeModel.LOCK, "find"), new PrivilegeModel.Cap(PrivilegeModel.LOCK, "update"));
    }
}
