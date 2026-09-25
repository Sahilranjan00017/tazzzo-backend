package com.tazzzo.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture/regression guard for {@code SnapshotTaxonomyReader}, in the
 * {@link NoBypassArchitectureIT} mould: source-token based, so it makes the common accidental
 * violation loud rather than proving violation impossible.
 *
 * <p>What it holds: the reader touches ONE collection, every query is release-bound through
 * {@code inRelease}, and nothing that would make it a live reader, a product reader or a consumer
 * policy reader is referenced anywhere in its code.
 */
class SnapshotTaxonomyReaderGuardIT {

    private static final Path READER =
            Path.of("src/main/java/com/tazzzo/catalog/schema/SnapshotTaxonomyReader.java");

    private static final List<String> FORBIDDEN = List.of(
            "\"taxonomy_nodes\"",             // the live tree — no fallback
            "TaxonomyService",                // the live reader — no fallback
            "\"products\"",                   // no product queries
            "\"offers_current\"",
            "\"consumer_projection_policy\"",
            "classification.release_id",      // provenance, never topology
            "\"aliases\"",
            "ConsumerEligibility");

    private String code() throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(READER).split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    void the_reader_never_references_live_taxonomy_products_or_consumer_state() throws IOException {
        String code = code();
        List<String> violations = new ArrayList<>();
        for (String token : FORBIDDEN) {
            if (code.contains(token)) violations.add(token);
        }
        assertThat(violations).as("snapshot reader must stay a snapshot reader").isEmpty();
    }

    @Test
    void the_reader_touches_exactly_one_collection() throws IOException {
        String code = code();
        long collectionLiterals = code.lines().filter(l -> l.contains("getCollection(")).count();
        long snapshotOnly = code.lines()
                .filter(l -> l.contains("getCollection(SNAPSHOT_COLLECTION)")).count();
        assertThat(collectionLiterals).isGreaterThan(0);
        assertThat(snapshotOnly)
                .as("every getCollection(...) must name the snapshot collection constant")
                .isEqualTo(collectionLiterals);
    }

    @Test
    void every_query_is_release_bound_through_inRelease() throws IOException {
        String code = code();
        List<String> unbound = code.lines()
                .filter(l -> l.contains(".find("))
                .filter(l -> !l.contains("inRelease("))
                .toList();
        assertThat(unbound)
                .as("a .find( that does not go through inRelease( is an unscoped read")
                .isEmpty();
    }
}
