package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards for the LIST path (LIST-1 §3b.1, PAGE-SIZE-4, LIST-CURSOR-1). These read the
 * source, not the runtime: they make the forbidden shapes impossible to reintroduce quietly.
 */
class ConsumerListGuardIT {

    private static final Path CONSUMER = Path.of("src/main/java/com/tazzzo/catalog/consumer");
    private static final Path LIST_SERVICE = CONSUMER.resolve("ConsumerProductListService.java");
    private static final Path CODEC = CONSUMER.resolve("ConsumerCursorCodec.java");

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
    }

    private static List<Path> consumerSources() throws IOException {
        try (var files = Files.walk(CONSUMER)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** PAGE-SIZE-4 / PAG-2: keyset only. No offset, no count — anywhere on the consumer plane. */
    @Test
    void no_consumer_source_uses_skip_or_a_count_query() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : consumerSources()) {
            String code = stripComments(Files.readString(f));
            for (String forbidden : new String[]{".skip(", "countDocuments(", "estimatedDocumentCount(",
                    ".count(", "Aggregates.count", "Aggregates.skip"}) {
                if (code.contains(forbidden)) {
                    violations.add(f.getFileName() + " uses " + forbidden);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    /** PHASE-5-BATCH-1: the page is projected ONCE. The per-item method is not the list path. */
    @Test
    void the_list_service_projects_the_page_with_projectAll_never_per_item() throws IOException {
        String code = stripComments(Files.readString(LIST_SERVICE));
        assertThat(code).contains("projection.projectAll(");
        assertThat(code).doesNotContain("projection.project(");
        assertThat(code).doesNotContain(".stream().map(projection::project");
    }

    /** LIST-1 §7: the page read carries the N+1 limit and only the five projected fields. */
    @Test
    void the_page_read_is_limited_to_page_size_plus_one_and_projected() throws IOException {
        String code = stripComments(Files.readString(LIST_SERVICE)).replaceAll("\\s+", "");
        assertThat(code).contains(".limit(pageSize+1)");
        assertThat(code).contains("Projections.include(PAGE_FIELDS)");
        assertThat(code).contains("Sorts.ascending(\"_id\")");
        assertThat(code).doesNotContain("Sorts.descending");
    }

    /** LIST-1 §5-6: the charge is issued before the scope probe, which is before the page read. */
    @Test
    void the_list_service_charges_before_it_probes_and_probes_before_it_reads() throws IOException {
        String code = stripComments(Files.readString(LIST_SERVICE));
        int charge = code.indexOf("gate.charge(ConsumerObservability.Route.LIST, identity, 1L + pageSize)");
        int probe = code.indexOf("probe.hasEligibleProduct(");
        int read = code.indexOf("db.getCollection(\"products\")");
        assertThat(charge).isPositive();
        assertThat(probe).isGreaterThan(charge);
        assertThat(read).isGreaterThan(probe);
    }

    /** LIST-CURSOR-1: constant-time comparison; the key material stays inside the codec. */
    @Test
    void the_codec_compares_in_constant_time_and_never_stringifies_the_key() throws IOException {
        String code = stripComments(Files.readString(CODEC));
        assertThat(code).contains("MessageDigest.isEqual(");
        assertThat(code).doesNotContain("Arrays.equals(");
        assertThat(code).doesNotContain(".equals(presented)");
        assertThat(code).doesNotContain("getCursorHmacKeyB64() +");
        assertThat(code).doesNotContain("configured +");
        assertThat(code).doesNotContain("Logger");
        for (Path f : consumerSources()) {
            if (f.equals(CODEC) || f.getFileName().toString().equals("ConsumerCursorProperties.java")) continue;
            assertThat(stripComments(Files.readString(f)))
                    .as(f.getFileName() + " must not touch the signing key")
                    .doesNotContain("getCursorHmacKeyB64");
        }
    }

    /** The two seams exist once; no consumer service re-implements admission or the probe. */
    @Test
    void admission_and_the_probe_have_exactly_one_implementation_each() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path f : consumerSources()) {
            String name = f.getFileName().toString();
            String code = stripComments(Files.readString(f));
            if (!name.equals("ConsumerAdmissionGate.java") && code.contains("rateLimiter.admit(")) {
                violations.add(name + " calls the limiter directly");
            }
            if (!name.equals("ConsumerAdmissionGate.java") && code.contains("limiter.getIfAvailable()")) {
                violations.add(name + " resolves the limiter directly");
            }
            if (!name.equals("ConsumerVisibilityProbe.java") && code.contains(".limit(1)")) {
                violations.add(name + " issues its own existence probe");
            }
        }
        assertThat(violations).isEmpty();
    }
}
