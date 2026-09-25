package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q4-c — the consumer transport boundary, now that a consumer controller exists.
 *
 * <pre>
 *   ConsumerTaxonomyController -> ConsumerTaxonomyService -> consumer DTOs only
 * </pre>
 *
 * <p>This REPLACES the temporary "no /catalog/v1 controller yet" tripwire that lived in
 * {@code OpenApiProtectionIT}. That guard existed to notice the first consumer controller arriving;
 * it has done its job, and deleting it without a successor would have removed the protection rather
 * than graduating it.
 *
 * <p>Source-token based, with the same honest limit as the other architecture guards: it makes the
 * common accidental violation loud, not impossible.
 */
class ConsumerTransportGuardIT {

    private static final Path CONTROLLER = Path.of(
            "src/main/java/com/tazzzo/catalog/consumer/ConsumerTaxonomyController.java");

    /** Persistence, transactions, audit events and the CMS DTOs are all off-limits to transport. */
    private static final List<String> FORBIDDEN_IN_CONTROLLER = List.of(
            "ApiDtos",                       // the CMS response types
            "org.bson.Document",             // raw persistence shapes
            "MongoDatabase", "MongoCollection", "com.mongodb.client.model.Filters",
            "WritePath", "ReleaseGate", "EventPayload",
            "com.tazzzo.catalog.tx.",        // no direct service-layer reach-through
            "ProductQueryService");

    private String code(Path file) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(file).split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    void the_consumer_controller_reaches_neither_persistence_nor_the_CMS_dtos() throws IOException {
        String code = code(CONTROLLER);
        List<String> violations = new ArrayList<>();
        for (String token : FORBIDDEN_IN_CONTROLLER) {
            if (code.contains(token)) {
                violations.add(token);
            }
        }
        assertThat(violations)
                .as("consumer transport calls a consumer service and returns consumer DTOs")
                .isEmpty();
    }

    @Test
    void every_handler_returns_a_consumer_dto() throws IOException {
        String code = code(CONTROLLER);
        List<String> handlers = new ArrayList<>();
        String[] lines = code.split("\n");
        for (int i = 0; i < lines.length; i++) {
            // Method-level mappings only: the class-level @RequestMapping("/catalog/v1") is a
            // base path, not a handler.
            boolean methodMapping = lines[i].contains("@GetMapping") || lines[i].contains("@PostMapping")
                    || lines[i].contains("@PutMapping") || lines[i].contains("@PatchMapping")
                    || lines[i].contains("@DeleteMapping");
            if (methodMapping) {
                // the return type is on this line or the next non-annotation line
                for (int j = i + 1; j < Math.min(i + 6, lines.length); j++) {
                    String candidate = lines[j].trim();
                    if (candidate.startsWith("public ")) {
                        handlers.add(candidate);
                        break;
                    }
                }
            }
        }
        assertThat(handlers).as("at least one mapped handler exists to check").isNotEmpty();
        for (String handler : handlers) {
            assertThat(handler)
                    .as("handler must return a consumer DTO: " + handler)
                    .contains("ConsumerDtos.");
        }
    }

    /** The whole consumer package must not import the CMS transport types either. */
    @Test
    void the_consumer_package_never_imports_the_CMS_response_dtos() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java/com/tazzzo/catalog/consumer"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (code(f).contains("ApiDtos")) {
                    violations.add(f.getFileName().toString());
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
