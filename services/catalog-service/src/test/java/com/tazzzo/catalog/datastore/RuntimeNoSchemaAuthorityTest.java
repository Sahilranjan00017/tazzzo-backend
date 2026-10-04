package com.tazzzo.catalog.datastore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime privilege profile is only honest if the runtime code truly never needs schema authority. This is the
 * structural guarantee: outside the {@code catalog.migration} and {@code catalog.schema} packages no main class
 * issues DDL, lists indexes or collections, runs a collection/validator command, or reaches the schema classes. (The one
 * {@code runCommand} outside them is the readiness {@code ping}.) The real-database tests then prove the other half: the
 * runtime identity can do everything the application does with exactly the privileges of its role.
 */
class RuntimeNoSchemaAuthorityTest {

    static final Path MAIN = Path.of("src/main/java");
    static final List<String> SCHEMA_PACKAGES = List.of("com/tazzzo/catalog/migration/", "com/tazzzo/catalog/schema/",
            "com/tazzzo/catalog/datastore/");

    static final Pattern DDL = Pattern.compile("\\.(createIndex|createIndexes|dropIndex|dropIndexes|createCollection|renameCollection|"
            + "listIndexes|listCollectionNames|listCollections|estimatedDocumentCount)\\(|\\.drop\\(\\)|\"collMod\"");
    static final Pattern RUN_COMMAND = Pattern.compile("\\.runCommand\\(");
    static final Pattern SCHEMA_TYPES = Pattern.compile("import com\\.tazzzo\\.catalog\\.(schema\\.(SchemaBootstrap|ValidatorGenerator|TaxonomyLoader)|migration\\.[A-Za-z]+);");

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static List<Path> runtimeSources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> SCHEMA_PACKAGES.stream().noneMatch(pkg -> p.toString().replace('\\', '/').contains(pkg)))
                    .toList();
        }
    }

    @Test
    void no_runtime_class_issues_ddl_or_schema_introspection() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : runtimeSources()) {
            String code = stripComments(Files.readString(f));
            var m = DDL.matcher(code);
            while (m.find()) violations.add(f + " -> " + m.group());
        }
        assertThat(violations).as("runtime code must hold no schema authority").isEmpty();
    }

    @Test
    void the_only_runtime_run_command_is_the_readiness_ping() throws IOException {
        List<String> uses = new ArrayList<>();
        for (Path f : runtimeSources()) {
            String code = stripComments(Files.readString(f));
            var m = RUN_COMMAND.matcher(code);
            while (m.find()) {
                int end = code.indexOf(';', m.end());
                uses.add(f.getFileName() + ": " + code.substring(m.start(), end).replaceAll("\\s+", " "));
            }
        }
        assertThat(uses).hasSize(1);
        assertThat(uses.get(0)).startsWith("CommerceReadReadiness.java").contains("\"ping\"");
    }

    @Test
    void no_runtime_class_reaches_the_schema_or_migration_classes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : runtimeSources()) {
            String code = stripComments(Files.readString(f));
            var m = SCHEMA_TYPES.matcher(code);
            while (m.find()) violations.add(f + " imports " + m.group(1));
        }
        assertThat(violations).as("only the migration/schema/datastore packages may use the schema classes").isEmpty();
    }

    @Test
    void the_scanner_actually_detects_what_it_claims_to() {
        assertThat(DDL.matcher("db.getCollection(\"x\").createIndex(k);").find()).isTrue();
        assertThat(DDL.matcher("coll.drop();").find()).isTrue();
        assertThat(DDL.matcher("new Document(\"collMod\", c)").find()).isTrue();
        assertThat(DDL.matcher("db.listCollectionNames()").find()).isTrue();
        assertThat(DDL.matcher("coll.insertOne(d); coll.find(f); coll.updateOne(a,b);").find()).isFalse();
        assertThat(stripComments("a(); // createIndex(\n/* dropIndex( */ b();")).doesNotContain("createIndex").doesNotContain("dropIndex");
    }
}
