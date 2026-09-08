package com.tazzzo.catalog;

import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.junit.jupiter.api.Test;

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
