package com.tazzzo.catalog.datastore;

import com.tazzzo.catalog.schema.SchemaBootstrap;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role definitions shipped under docs/database/roles (for the infrastructure track) are GENERATED from
 * {@link PrivilegeModel} and must equal it. If the collection roster or the model changes, this fails until the files are
 * regenerated: {@code ./mvnw test -Dtest=RoleFilesTest -Dtazzzo.regenerate-role-files=true}.
 *
 * <p>The files are MongoDB {@code createRole} command documents (the form the real-database tests apply). The
 * staging database name {@code tazzzo_staging} is the one established in the infrastructure repository; substitute the
 * database name of another environment.
 */
class RoleFilesTest {

    static final String DB = "tazzzo_staging";
    static final Path DIR = Path.of("../../docs/database/roles");
    static final JsonWriterSettings PRETTY = JsonWriterSettings.builder().indent(true).build();

    static final Map<String, Object[]> FILES = Map.of(
            "tazzzo-runtime.role.json", new Object[]{"tazzzo_runtime", PrivilegeModel.Spec.RUNTIME},
            "tazzzo-migrator.role.json", new Object[]{"tazzzo_migrator", PrivilegeModel.Spec.MIGRATOR},
            "tazzzo-migration-reader.role.json", new Object[]{"tazzzo_migration_reader", PrivilegeModel.Spec.READER});

    private static String generated(String role, PrivilegeModel.Spec spec) {
        return PrivilegeModel.createRole(role, spec, DB, SchemaBootstrap.COLLECTIONS).toJson(PRETTY) + "\n";
    }

    @Test
    void the_committed_role_files_equal_the_generated_definitions() throws IOException {
        boolean regenerate = Boolean.getBoolean("tazzzo.regenerate-role-files");
        Files.createDirectories(DIR);
        for (var e : FILES.entrySet()) {
            Path file = DIR.resolve(e.getKey());
            String expected = generated((String) e.getValue()[0], (PrivilegeModel.Spec) e.getValue()[1]);
            if (regenerate) {
                Files.writeString(file, expected);
            }
            assertThat(file).as("missing role file " + file + " (regenerate with -Dtazzzo.regenerate-role-files=true)").exists();
            assertThat(Files.readString(file)).as(e.getKey() + " is stale: regenerate with -Dtazzzo.regenerate-role-files=true").isEqualTo(expected);
        }
        try (var listing = Files.list(DIR)) {
            assertThat(listing.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).toList())
                    .as("no unexpected role file").containsExactlyInAnyOrderElementsOf(FILES.keySet());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_runtime_role_grants_data_access_on_every_collection_and_nothing_else() throws IOException {
        Document role = Document.parse(Files.readString(DIR.resolve("tazzzo-runtime.role.json")));
        List<Document> privileges = role.getList("privileges", Document.class);
        TreeSet<String> withWrites = new TreeSet<>();
        for (Document p : privileges) {
            Document resource = p.get("resource", Document.class);
            assertThat(resource.getString("db")).isEqualTo(DB);
            List<String> actions = p.getList("actions", String.class);
            assertThat(actions).doesNotContain("createIndex", "dropIndex", "collMod", "createCollection", "dropCollection",
                    "dropDatabase", "renameCollectionSameDB", "anyAction");
            if (actions.contains("insert")) {
                withWrites.add(resource.getString("collection"));
            }
            if (resource.getString("collection").isEmpty()) {
                assertThat(actions).as("the only database-wide runtime grant is listCollections").containsExactly("listCollections");
            }
        }
        assertThat(withWrites).as("write access exactly on the application roster, never on the migration bookkeeping")
                .isEqualTo(new TreeSet<>(SchemaBootstrap.COLLECTIONS))
                .doesNotContain(PrivilegeModel.HISTORY, PrivilegeModel.LOCK);
        assertThat(role.getList("roles", Object.class)).as("it inherits nothing, in particular no built-in readWrite").isEmpty();
    }

    @Test
    void the_migrator_role_has_schema_authority_but_no_blanket_write_and_no_destructive_action() throws IOException {
        Document role = Document.parse(Files.readString(DIR.resolve("tazzzo-migrator.role.json")));
        for (Document p : role.getList("privileges", Document.class)) {
            List<String> actions = p.getList("actions", String.class);
            assertThat(actions).doesNotContain("dropCollection", "dropDatabase", "remove", "anyAction", "createUser", "createRole");
            if (p.get("resource", Document.class).getString("collection").isEmpty()) {
                assertThat(actions).as("database-wide: schema authority and reads only, never data writes")
                        .doesNotContain("insert", "update");
            }
        }
        assertThat(role.getList("roles", Object.class)).isEmpty();
    }
}
