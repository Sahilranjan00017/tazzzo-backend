package com.tazzzo.catalog.datastore;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.datastore.DatastoreStartupVerifier.Enforcement;
import com.tazzzo.catalog.migration.MigrationHistory;
import com.tazzzo.catalog.migration.MigrationLock;
import com.tazzzo.catalog.migration.MigrationMode;
import com.tazzzo.catalog.migration.MigrationProperties;
import com.tazzzo.catalog.migration.MigrationRunner;
import com.tazzzo.catalog.migration.MigrationRunner.StepStatus;
import com.tazzzo.catalog.migration.MigrationTarget;
import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.DB;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.MIGRATOR_USER;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.READER_USER;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.RUNTIME_USER;
import static com.tazzzo.catalog.datastore.AuthenticatedReplicaSet.USER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-4 against a REAL authenticated MongoDB replica set: the three roles generated from {@link PrivilegeModel} (the
 * definitions shipped under docs/database/roles) are applied to real database users, and each identity is exercised
 * for real. Nothing here is simulated: an operation the role does not allow fails with the server's own
 * {@code Unauthorized} (code 13).
 */
class DatastorePrivilegeIT {

    static final String V0004 = "V0004__seed_schemas_pack_fields_not_required";
    static final Set<String> BOOKKEEPING = Set.of(MigrationHistory.COLLECTION, MigrationLock.COLLECTION);
    static final int UNAUTHORIZED = 13;
    static final int DOCUMENT_VALIDATION_FAILED = 121;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<MongoClient> clients = new ArrayList<>();

    @BeforeAll
    static void up() {
        AuthenticatedReplicaSet.start();
    }

    @AfterAll
    static void noop() {
        // the container is a JVM-lifetime singleton (Ryuk removes it)
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        contexts.forEach(ConfigurableApplicationContext::close);
        contexts.clear();
        clients.forEach(MongoClient::close);
        clients.clear();
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Runs the real application as a one-shot NON-WEB job under {@code user}'s identity. A failed job throws. */
    private ConfigurableApplicationContext job(String user, MigrationMode mode, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.data.mongodb.uri=" + AuthenticatedReplicaSet.uri(user, USER_PASSWORD, DB),
                "--spring.data.mongodb.database=" + DB,
                "--tazzzo.migration.mode=" + mode,
                "--tazzzo.migration.environment=dev",
                "--tazzzo.migration.exit-after-run=false",
                "--tazzzo.schema.load-taxonomy-seed=false",
                "--tazzzo.scheduler.enabled=false",
                "--tazzzo.consumer-rate-limit.mode=DISABLED"));
        args.addAll(List.of(extra));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(CatalogApplication.class)
                .web(WebApplicationType.NONE).run(args.toArray(String[]::new));
        contexts.add(ctx);
        return ctx;
    }

    private MongoClient as(String user) {
        MongoClient c = AuthenticatedReplicaSet.client(user);
        clients.add(c);
        return c;
    }

    private static MongoDatabase root() {
        return AuthenticatedReplicaSet.root().getDatabase(DB);
    }

    private static boolean migrated() {
        MongoCollection<Document> h = root().getCollection(MigrationHistory.COLLECTION);
        return h.countDocuments(new Document("status", "APPLIED")) >= 7;
    }

    /** A migrated database (applied by the migrator identity, with the data migration approved). */
    private void ensureMigrated() {
        if (migrated()) {
            return;
        }
        AuthenticatedReplicaSet.resetDatabase();
        job(MIGRATOR_USER, MigrationMode.APPLY, "--tazzzo.migration.approved-data-migrations=" + V0004);
        assertThat(migrated()).isTrue();
    }

    private static void assertUnauthorized(String what, Runnable op) {
        assertThatThrownBy(op::run).as(what).isInstanceOf(MongoException.class)
                .satisfies(e -> assertThat(((MongoException) e).getCode()).as(what + ": server error code").isEqualTo(UNAUTHORIZED));
    }

    private static DatastoreStartupVerifier.Result live(MongoClient client, MigrationMode mode) {
        MigrationProperties m = new MigrationProperties();
        m.setEnvironment("staging");
        m.setMode(mode);
        // the contract stage sees a compliant URI; the live stages use the REAL client of the identity under test
        return new DatastoreStartupVerifier(client, DB, m, new DatastoreProperties(), ConnectionContractTest.RS,
                SchemaBootstrap.COLLECTIONS).verify();
    }

    // ---- the jobs, as the real application, under their own identities -----------------------

    @Test
    void a_read_only_identity_dry_runs_an_empty_database_and_creates_nothing() {
        AuthenticatedReplicaSet.resetDatabase();
        job(READER_USER, MigrationMode.DRY_RUN);
        assertThat(root().listCollectionNames()).as("a dry run creates nothing, not even the history or the lock").isEmpty();
    }

    @Test
    void the_migrator_applies_every_migration_then_a_read_only_dry_run_shows_nothing_pending_and_a_rerun_changes_nothing() {
        AuthenticatedReplicaSet.resetDatabase();
        job(MIGRATOR_USER, MigrationMode.APPLY, "--tazzzo.migration.approved-data-migrations=" + V0004);

        Set<String> applied = new TreeSet<>();
        root().getCollection(MigrationHistory.COLLECTION).find(new Document("status", "APPLIED"))
                .forEach(d -> applied.add(d.getString("_id")));
        assertThat(applied).hasSize(8).allMatch(id -> id.startsWith("V000"));
        Set<String> expected = new TreeSet<>(SchemaBootstrap.COLLECTIONS);
        expected.addAll(BOOKKEEPING);
        assertThat(new TreeSet<>(root().listCollectionNames().into(new ArrayList<>()))).isEqualTo(expected);

        // second dry run, as the READ-ONLY identity: zero pending
        ConfigurableApplicationContext reader = job(READER_USER, MigrationMode.DRY_RUN);
        MigrationRunner.RunReport report = reader.getBean(MigrationRunner.class).dryRun(
                new MigrationTarget("dev", DB, List.of("localhost:27017"), "it", "it"),
                MigrationRunner.Selection.all().withApproved(Set.of(V0004)));
        assertThat(report.outcome()).isEqualTo(MigrationRunner.Outcome.OK);
        assertThat(report.steps()).isNotEmpty().allSatisfy(s -> assertThat(s.status()).as(s.id()).isEqualTo(StepStatus.ALREADY_APPLIED));

        // restart-safe: another APPLY changes nothing (history documents are untouched)
        Document before = root().getCollection(MigrationHistory.COLLECTION).find(new Document("_id", "V0007__audit_read_partial_indexes")).first();
        job(MIGRATOR_USER, MigrationMode.APPLY, "--tazzzo.migration.approved-data-migrations=" + V0004);
        Document after = root().getCollection(MigrationHistory.COLLECTION).find(new Document("_id", "V0007__audit_read_partial_indexes")).first();
        assertThat(after.get("attempts")).isEqualTo(before.get("attempts"));
        assertThat(after.get("appliedAt")).isEqualTo(before.get("appliedAt"));
    }

    @Test
    void the_application_in_verify_mode_starts_as_the_runtime_identity_and_holds_no_schema_authority() {
        ensureMigrated();
        ConfigurableApplicationContext app = job(RUNTIME_USER, MigrationMode.VERIFY);
        assertThat(app.isRunning()).isTrue();
    }

    @Test
    void verify_mode_refuses_an_unmigrated_database_even_as_the_runtime_identity() {
        AuthenticatedReplicaSet.resetDatabase();
        assertThatThrownBy(() -> job(RUNTIME_USER, MigrationMode.VERIFY)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database schema verification failed").hasMessageContaining("no migration history exists");
        ensureMigrated();
    }

    // ---- the runtime identity ----------------------------------------------------------------

    @Test
    void the_runtime_identity_can_read_write_and_transact_on_every_application_collection() {
        ensureMigrated();
        MongoClient app = as(RUNTIME_USER);
        MongoDatabase db = app.getDatabase(DB);
        for (String c : SchemaBootstrap.COLLECTIONS) {
            MongoCollection<Document> coll = db.getCollection(c);
            Document probe = new Document("_id", "probe-" + c).append("probe", 1);
            try {
                coll.insertOne(probe);
            } catch (MongoException e) {
                // an application validator may reject the probe shape: that is AUTHORIZED and then refused on content
                assertThat(e.getCode()).as(c + ": insert must be authorized").isIn(DOCUMENT_VALIDATION_FAILED);
                continue;
            }
            assertThat(coll.find(new Document("_id", probe.get("_id"))).first()).as(c + ": find").isNotNull();
            assertThat(coll.updateOne(new Document("_id", probe.get("_id")), new Document("$set", new Document("probe", 2)))
                    .getModifiedCount()).as(c + ": update").isEqualTo(1);
            assertThat(coll.deleteOne(new Document("_id", probe.get("_id"))).getDeletedCount()).as(c + ": remove").isEqualTo(1);
        }
        assertThat(db.listCollectionNames().into(new ArrayList<>())).contains("products", MigrationHistory.COLLECTION);

        // a multi-document transaction over two collections commits, as every application write does
        try (var session = app.startSession()) {
            session.withTransaction(() -> {
                db.getCollection("work_queue").insertOne(session, new Document("_id", "txn-a").append("n", 1));
                db.getCollection("id_sequences").insertOne(session, new Document("_id", "txn-b").append("n", 1));
                return null;
            });
        }
        assertThat(db.getCollection("work_queue").deleteOne(new Document("_id", "txn-a")).getDeletedCount()).isEqualTo(1);
        assertThat(db.getCollection("id_sequences").deleteOne(new Document("_id", "txn-b")).getDeletedCount()).isEqualTo(1);
    }

    @Test
    void the_runtime_identity_is_denied_every_schema_action() {
        ensureMigrated();
        MongoDatabase db = as(RUNTIME_USER).getDatabase(DB);
        assertUnauthorized("createIndex", () -> db.getCollection("orders").createIndex(new Document("zz", 1)));
        assertUnauthorized("dropIndex", () -> db.getCollection("products").dropIndex("product_vertical_id_cursor"));
        assertUnauthorized("createCollection", () -> db.createCollection("some_new_collection"));
        assertUnauthorized("collMod", () -> db.runCommand(new Document("collMod", "orders").append("validator", new Document())));
        assertUnauthorized("dropCollection", () -> db.getCollection("work_queue").drop());
        assertUnauthorized("dropDatabase", db::drop);
        assertUnauthorized("renameCollection", () -> as(RUNTIME_USER).getDatabase("admin").runCommand(new Document("renameCollection", DB + ".work_queue")
                .append("to", DB + ".work_queue_renamed")));
        assertUnauthorized("createUser", () -> db.runCommand(new Document("createUser", "evil").append("pwd", "x").append("roles", List.of())));
        assertUnauthorized("createRole", () -> as(RUNTIME_USER).getDatabase("admin").runCommand(new Document("createRole", "evil")
                .append("privileges", List.of()).append("roles", List.of())));
        assertThat(db.listCollectionNames().into(new ArrayList<>())).as("nothing was created").doesNotContain("some_new_collection", "work_queue_renamed");
    }

    @Test
    void the_runtime_identity_can_read_but_never_write_the_migration_history_and_cannot_touch_the_lock() {
        ensureMigrated();
        MongoDatabase db = as(RUNTIME_USER).getDatabase(DB);
        assertThat(db.getCollection(MigrationHistory.COLLECTION).countDocuments()).isGreaterThanOrEqualTo(7);
        assertUnauthorized("forge history", () -> db.getCollection(MigrationHistory.COLLECTION)
                .insertOne(new Document("_id", "V9999__forged").append("status", "APPLIED")));
        assertUnauthorized("edit history", () -> db.getCollection(MigrationHistory.COLLECTION)
                .updateOne(new Document(), new Document("$set", new Document("status", "FAILED"))));
        assertUnauthorized("delete history", () -> db.getCollection(MigrationHistory.COLLECTION).deleteMany(new Document()));
        assertUnauthorized("read the lock", () -> db.getCollection(MigrationLock.COLLECTION).find().first());
        assertUnauthorized("write the lock", () -> db.getCollection(MigrationLock.COLLECTION)
                .updateOne(new Document(), new Document("$set", new Document("x", 1))));
    }

    @Test
    void the_runtime_identity_cannot_reach_other_databases_or_the_admin_database() {
        ensureMigrated();
        MongoClient app = as(RUNTIME_USER);
        assertUnauthorized("other database", () -> app.getDatabase("some_other_db").getCollection("x").find().first());
        assertUnauthorized("other database write", () -> app.getDatabase("some_other_db").getCollection("x").insertOne(new Document("a", 1)));
        assertUnauthorized("admin users", () -> app.getDatabase("admin").getCollection("system.users").find().first());
        assertUnauthorized("config", () -> app.getDatabase("config").getCollection("system.sessions").find().first());
    }

    // ---- the migrator identity ---------------------------------------------------------------

    @Test
    void the_migrator_identity_has_schema_authority_but_cannot_write_business_data_or_destroy_anything() {
        ensureMigrated();
        MongoDatabase db = as(MIGRATOR_USER).getDatabase(DB);
        assertUnauthorized("write an order", () -> db.getCollection("orders").insertOne(new Document("_id", "x")));
        assertUnauthorized("write a customer", () -> db.getCollection("customers").insertOne(new Document("_id", "x")));
        assertUnauthorized("edit an order", () -> db.getCollection("orders").updateOne(new Document(), new Document("$set", new Document("a", 1))));
        assertUnauthorized("delete an order", () -> db.getCollection("orders").deleteMany(new Document()));
        assertUnauthorized("dropCollection", () -> db.getCollection("work_queue").drop());
        assertUnauthorized("dropDatabase", db::drop);
        assertUnauthorized("createUser", () -> db.runCommand(new Document("createUser", "evil").append("pwd", "x").append("roles", List.of())));
        assertUnauthorized("other database", () -> as(MIGRATOR_USER).getDatabase("some_other_db").getCollection("x").find().first());
        // but it can do what migrations do: DDL (a throw-away collection and index), then it is cleaned up by the root identity
        db.createCollection("migrator_probe");
        db.getCollection("migrator_probe").createIndex(new Document("a", 1));
        db.getCollection("migrator_probe").dropIndex("a_1");
        assertUnauthorized("cannot drop what it created", () -> db.getCollection("migrator_probe").drop());
        root().getCollection("migrator_probe").drop();
    }

    // ---- the verifier's live stages against the real identities ------------------------------

    @Test
    void the_runtime_identity_passes_the_live_verification_as_the_runtime() {
        ensureMigrated();
        DatastoreStartupVerifier.Result r = live(as(RUNTIME_USER), MigrationMode.VERIFY);
        assertThat(r.enforcement()).isEqualTo(Enforcement.ENFORCED);
        assertThat(r.violations()).isEmpty();
        assertThat(r.summary()).contains("profile=RUNTIME", "privileges=ok");
    }

    @Test
    void the_migrator_identity_is_refused_as_the_runtime_because_it_carries_schema_authority() {
        ensureMigrated();
        DatastoreStartupVerifier.Result r = live(as(MIGRATOR_USER), MigrationMode.VERIFY);
        assertThat(r.violations()).extracting(Violation::code).contains("EXCESS_PRIVILEGE");
        assertThat(r.violations().toString()).contains("createIndex is held", "dropIndex is held", "collMod is held");
        assertThatThrownBy(() -> new DatastoreStartupVerifier(as(MIGRATOR_USER), DB, props(MigrationMode.VERIFY), new DatastoreProperties(),
                ConnectionContractTest.RS, SchemaBootstrap.COLLECTIONS).run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(DatastoreContractException.class).hasMessageContaining("refusing to start");
    }

    @Test
    void the_migration_identities_pass_for_their_jobs_and_fail_for_the_wrong_one() {
        ensureMigrated();
        assertThat(live(as(READER_USER), MigrationMode.DRY_RUN).violations()).isEmpty();
        assertThat(live(as(MIGRATOR_USER), MigrationMode.DRY_RUN).violations()).as("the apply identity may also dry-run").isEmpty();
        assertThat(live(as(MIGRATOR_USER), MigrationMode.APPLY).violations()).isEmpty();
        DatastoreStartupVerifier.Result readerApply = live(as(READER_USER), MigrationMode.APPLY);
        assertThat(readerApply.violations().toString()).contains("createIndex is required", "collMod is required");
        DatastoreStartupVerifier.Result runtimeDry = live(as(RUNTIME_USER), MigrationMode.DRY_RUN);
        assertThat(runtimeDry.violations().toString()).contains("listIndexes is required");
    }

    @Test
    void a_superuser_is_refused_for_every_profile() {
        MongoClient superuser = AuthenticatedReplicaSet.root();
        for (MigrationMode mode : new MigrationMode[]{MigrationMode.VERIFY, MigrationMode.DRY_RUN, MigrationMode.APPLY}) {
            DatastoreStartupVerifier.Result r = live(superuser, mode);
            assertThat(r.violations()).as(mode.toString()).extracting(Violation::code).contains("OUT_OF_SCOPE_PRIVILEGE");
        }
    }

    @Test
    void wrong_credentials_and_an_unreachable_server_fail_clearly_without_leaking_anything() {
        String badPassword = "WrongPw-9f8e7d6c5b4a";
        MongoClient wrong = MongoClients.create(AuthenticatedReplicaSet.uri(RUNTIME_USER, badPassword, DB));
        clients.add(wrong);
        DatastoreStartupVerifier.Result auth = live(wrong, MigrationMode.VERIFY);
        assertThat(auth.violations()).extracting(Violation::code).containsExactly("AUTHENTICATION_FAILED");
        assertThat(auth.violations().toString()).doesNotContain(badPassword).doesNotContain(RUNTIME_USER).doesNotContain("authSource");

        MongoClient down = MongoClients.create(AuthenticatedReplicaSet.unreachableUri());
        clients.add(down);
        DatastoreStartupVerifier.Result unavailable = live(down, MigrationMode.VERIFY);
        assertThat(unavailable.violations()).extracting(Violation::code).containsExactly("DATASTORE_UNAVAILABLE");
        assertThat(unavailable.violations().toString()).doesNotContain("127.0.0.1");

        MongoClient anonymous = MongoClients.create(AuthenticatedReplicaSet.uriWithoutCredentials());
        clients.add(anonymous);
        DatastoreStartupVerifier.Result none = live(anonymous, MigrationMode.VERIFY);
        assertThat(none.violations()).as("an unauthenticated connection cannot be inspected or used")
                .extracting(Violation::code).containsAnyOf("NOT_AUTHENTICATED", "UNAUTHORIZED_COMMAND");
    }

    private static MigrationProperties props(MigrationMode mode) {
        MigrationProperties m = new MigrationProperties();
        m.setEnvironment("staging");
        m.setMode(mode);
        return m;
    }
}
