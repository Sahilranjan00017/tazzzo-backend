package com.tazzzo.catalog;

import com.mongodb.client.model.Collation;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A — PAG-2-SORT-1 product index. Proves the WIDENING, not merely that the new index
 * exists: MongoDB does not widen an index in place, so adding {@code _id} to the key list would
 * otherwise leave the superseded three-field prefix behind, paying write amplification forever for
 * an index no query needs.
 *
 * <p>These tests assert the exact key ORDER. A compound index is defined by its key sequence, and
 * {@code _id} must be last — a keyset cursor resumes on the terminal field.
 */
class ProductIndexMigrationIT extends AbstractMongoIT {

    private static final List<String> LEGACY =
            List.of("classification.vertical_id", "lifecycle", "classification.status");
    private static final List<String> PAG2 =
            List.of("classification.vertical_id", "lifecycle", "classification.status", "_id");

    /** Every index on products, as ordered ascending-key lists. */
    private List<List<String>> productIndexKeys() {
        List<List<String>> all = new ArrayList<>();
        for (Document index : db.getCollection("products").listIndexes()) {
            Document key = index.get("key", Document.class);
            if (key != null) {
                all.add(new ArrayList<>(key.keySet()));
            }
        }
        return all;
    }

    private long countMatching(List<String> keys) {
        return productIndexKeys().stream().filter(k -> k.equals(keys)).count();
    }

    // ---------- 1. fresh bootstrap ----------

    @Test
    void a_fresh_bootstrap_creates_the_four_field_index() {
        db.getCollection("products").dropIndexes();
        schemaBootstrap.bootstrap(db);

        assertThat(countMatching(PAG2))
                .as("the PAG-2 transport index must exist after a fresh bootstrap")
                .isEqualTo(1);
    }

    // ---------- 2. migration ----------

    @Test
    void bootstrap_removes_the_superseded_three_field_prefix_index() {
        db.getCollection("products").dropIndexes();
        db.getCollection("products").createIndex(Indexes.ascending(LEGACY));
        assertThat(countMatching(LEGACY)).as("precondition: the old index is present").isEqualTo(1);

        schemaBootstrap.bootstrap(db);

        assertThat(countMatching(LEGACY))
                .as("the prefix index is redundant once the wider one exists")
                .isZero();
        assertThat(countMatching(PAG2)).isEqualTo(1);
    }

    /** The old index is identified by KEY PATTERN — a generated name must not be assumed. */
    @Test
    void the_old_index_is_dropped_even_under_a_non_default_name() {
        db.getCollection("products").dropIndexes();
        db.getCollection("products").createIndex(Indexes.ascending(LEGACY),
                new com.mongodb.client.model.IndexOptions().name("legacy_handcrafted_name"));

        schemaBootstrap.bootstrap(db);

        assertThat(countMatching(LEGACY)).isZero();
        assertThat(productIndexKeys()).noneMatch(k -> k.equals(LEGACY));
    }

    // ---------- 2b. migration safety: same keys, DIFFERENT semantics -> never dropped ----------

    /**
     * The legacy candidate is identified by key pattern AND by carrying NOTHING beyond the metadata
     * listIndexes() reports for the plain historical index (observed on MongoDB 7.0.40: exactly
     * v, key, name). An index sharing the keys but carrying unique / sparse / a partial filter /
     * a collation / hidden was created deliberately for some other purpose; key equivalence is not
     * semantic equivalence, and dropIndex is destructive.
     *
     * <p>These named cases are structurally redundant under the whitelist and are kept as
     * documentation. The whitelist itself is proven by the unknown-field predicate test below.
     *
     * <p>TTL is not in this list because MongoDB refuses expireAfterSeconds on a compound index,
     * so that fixture cannot be created; it is exercised at predicate level.
     */
    static Stream<Arguments> optionBearingLegacyIndexes() {
        return Stream.of(
                Arguments.of("unique", new IndexOptions().unique(true)),
                Arguments.of("sparse", new IndexOptions().sparse(true)),
                Arguments.of("partialFilterExpression", new IndexOptions()
                        .partialFilterExpression(Filters.eq("lifecycle", "active"))),
                Arguments.of("collation", new IndexOptions()
                        .collation(Collation.builder().locale("en").build())),
                // hidden is the case a denylist of remembered options would have DROPPED.
                Arguments.of("hidden", new IndexOptions().hidden(true)));
    }

    @ParameterizedTest(name = "legacy keys + {0} survives bootstrap")
    @MethodSource("optionBearingLegacyIndexes")
    void a_legacy_key_pattern_with_different_options_is_not_dropped(String option,
                                                                     IndexOptions options) {
        db.getCollection("products").dropIndexes();
        db.getCollection("products").createIndex(Indexes.ascending(LEGACY), options);
        assertThat(countMatching(LEGACY)).as("precondition").isEqualTo(1);

        schemaBootstrap.bootstrap(db);

        assertThat(countMatching(LEGACY))
                .as("an index with option '" + option + "' is somebody else's index — never dropped")
                .isEqualTo(1);
        assertThat(countMatching(PAG2))
                .as("the wider index is still created alongside it")
                .isEqualTo(1);
    }

    /**
     * The whitelist proper: a field this code has NEVER HEARD OF must preserve the index. This is
     * the property a denylist cannot provide, and the reason for the posture.
     */
    @Test
    void the_predicate_preserves_an_index_carrying_an_unknown_field() {
        Document unknown = new Document("v", 2)
                .append("key", new Document("classification.vertical_id", 1)
                        .append("lifecycle", 1).append("classification.status", 1))
                .append("name", "whatever").append("someFutureServerOption", true);
        assertThat(com.tazzzo.catalog.schema.SchemaBootstrap
                .isHistoricalPlainPrefix(unknown, LEGACY))
                .as("fail conservative: an option we do not understand is not ours to drop")
                .isFalse();
    }

    /** Exactly the observed historical shape — and ONLY that — is what gets dropped. */
    @Test
    void the_predicate_accepts_exactly_the_observed_historical_shape() {
        Document observed = new Document("v", 2)
                .append("key", new Document("classification.vertical_id", 1)
                        .append("lifecycle", 1).append("classification.status", 1))
                .append("name", "classification.vertical_id_1_lifecycle_1_classification.status_1");
        assertThat(com.tazzzo.catalog.schema.SchemaBootstrap
                .isHistoricalPlainPrefix(observed, LEGACY)).isTrue();
    }

    /** The TTL guard, at the predicate level, since the fixture itself cannot exist in Mongo. */
    @Test
    void the_predicate_rejects_a_ttl_bearing_index_document() {
        Document ttlIndex = new Document("v", 2)
                .append("key", new Document("classification.vertical_id", 1)
                        .append("lifecycle", 1).append("classification.status", 1))
                .append("name", "whatever").append("expireAfterSeconds", 3600);
        assertThat(com.tazzzo.catalog.schema.SchemaBootstrap
                .isHistoricalPlainPrefix(ttlIndex, LEGACY)).isFalse();
    }

    /** Direction must be EXACTLY 1 — a value that merely truncates to 1 is not ascending. */
    @Test
    void the_predicate_requires_direction_exactly_one() {
        Document odd = new Document("v", 2)
                .append("key", new Document("classification.vertical_id", 1)
                        .append("lifecycle", 1.5).append("classification.status", 1))
                .append("name", "whatever");
        assertThat(com.tazzzo.catalog.schema.SchemaBootstrap
                .isHistoricalPlainPrefix(odd, LEGACY)).isFalse();

        Document plain = new Document("v", 2)
                .append("key", new Document("classification.vertical_id", 1)
                        .append("lifecycle", 1L).append("classification.status", 1.0))
                .append("name", "whatever");
        assertThat(com.tazzzo.catalog.schema.SchemaBootstrap
                .isHistoricalPlainPrefix(plain, LEGACY))
                .as("1, 1L and 1.0 are all exactly one").isTrue();
    }

    // ---------- 3. idempotence ----------

    @Test
    void rerunning_bootstrap_is_a_no_op() {
        db.getCollection("products").dropIndexes();
        schemaBootstrap.bootstrap(db);
        List<List<String>> afterFirst = productIndexKeys();

        schemaBootstrap.bootstrap(db);
        schemaBootstrap.bootstrap(db);

        assertThat(productIndexKeys())
                .as("an already-migrated database must not drift on rerun")
                .containsExactlyInAnyOrderElementsOf(afterFirst);
        assertThat(countMatching(PAG2)).isEqualTo(1);
    }

    // ---------- 4. they must not coexist ----------

    @Test
    void the_old_and_new_indexes_never_coexist_after_migration() {
        db.getCollection("products").dropIndexes();
        db.getCollection("products").createIndex(Indexes.ascending(LEGACY));
        schemaBootstrap.bootstrap(db);

        assertThat(countMatching(LEGACY) + countMatching(PAG2))
                .as("exactly one of the two, and it is the wider one")
                .isEqualTo(1);
        assertThat(countMatching(PAG2)).isEqualTo(1);
    }

    // ---------- 5. exact key order ----------

    @Test
    void the_index_key_order_is_exact_with_id_last() {
        db.getCollection("products").dropIndexes();
        schemaBootstrap.bootstrap(db);

        Document key = null;
        for (Document index : db.getCollection("products").listIndexes()) {
            Document k = index.get("key", Document.class);
            if (k != null && new ArrayList<>(k.keySet()).equals(PAG2)) {
                key = k;
            }
        }
        assertThat(key).isNotNull();
        assertThat(new ArrayList<>(key.keySet()))
                .containsExactly("classification.vertical_id", "lifecycle",
                        "classification.status", "_id");
        for (String field : PAG2) {
            assertThat(((Number) key.get(field)).intValue())
                    .as(field + " must be ASCENDING")
                    .isEqualTo(1);
        }
    }
}
