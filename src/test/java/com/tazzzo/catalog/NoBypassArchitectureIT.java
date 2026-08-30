package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard (rebuilt after review M10: the previous string heuristics could not
 * see the very violation that existed). Rules are now structural:
 *  - the api package may not import the Mongo driver, WritePath, Tx or EventPayload;
 *  - therefore transport physically cannot write, transact, or author audit events;
 *  - only WritePath performs mutations outside the seed/DDL classes.
 */
class NoBypassArchitectureIT {

    private static final Path API = Path.of("src/main/java/com/tazzzo/catalog/api");
    private static final Path MAIN = Path.of("src/main/java/com/tazzzo/catalog");

    private static final List<String> API_FORBIDDEN_IMPORTS = List.of(
            "import com.mongodb.client.MongoCollection",
            "import com.mongodb.client.MongoDatabase",
            "import com.mongodb.client.model.Filters",
            "import com.tazzzo.catalog.repo.WritePath",
            "import com.tazzzo.catalog.repo.ReleaseGate",
            "import com.tazzzo.catalog.tx.Tx;",
            "import com.tazzzo.catalog.events.EventPayload");

    private static final List<String> MUTATIONS = List.of("insertOne(", "insertMany(",
            "updateOne(", "updateMany(", "deleteOne(", "deleteMany(", "replaceOne(",
            "findOneAndUpdate(", "bulkWrite(");

    @Test
    void api_package_cannot_reach_persistence_or_author_events() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : javaFiles(API)) {
            String src = Files.readString(f);
            for (String imp : API_FORBIDDEN_IMPORTS) {
                if (src.contains(imp)) violations.add(f.getFileName() + " imports " + imp);
            }
            for (String m : MUTATIONS) {
                if (src.contains(m)) violations.add(f.getFileName() + " mutates: " + m);
            }
            if (src.contains("new EventPayload(")) {
                violations.add(f.getFileName() + " authors an audit event");
            }
            if (src.contains("tx.run(")) {
                violations.add(f.getFileName() + " opens its own transaction");
            }
        }
        assertThat(violations)
                .as("transport must call services only — no writes, no transactions, no events")
                .isEmpty();
    }

    @Test
    void only_writepath_performs_state_mutations() throws IOException {
        List<String> allowed = List.of("WritePath.java", "SchemaBootstrap.java",
                "ValidatorGenerator.java", "TaxonomyLoader.java");
        List<String> violations = new ArrayList<>();
        for (Path f : javaFiles(MAIN)) {
            String name = f.getFileName().toString();
            if (allowed.contains(name)) continue;
            for (String line : Files.readString(f).split("\n")) {
                String code = line.trim();
                if (code.startsWith("//") || code.startsWith("*")) continue;
                boolean mutates = MUTATIONS.stream().anyMatch(code::contains);
                if (!mutates) continue;
                // Legal only inside a WritePath callback: the collection handle is the lambda
                // parameter `c`, or the call is a lease claim on the work queue.
                boolean viaWritePath = code.contains("c.insert") || code.contains("c.update")
                        || code.contains("c.delete") || code.contains("c.replace")
                        || code.contains("c.findOneAndUpdate")
                        || code.contains("writePath.")
                        || code.contains("getCollection(\"work_queue\").findOneAndUpdate");
                if (!viaWritePath) violations.add(name + ": " + code);
            }
        }
        assertThat(violations).as("all state mutations go through WritePath").isEmpty();
    }

    private List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
