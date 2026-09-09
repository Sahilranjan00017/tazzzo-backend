package com.tazzzo.catalog;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3B hardening — the snapshot traversal index exists, is idempotent, and is ADDITIVE. */
class SnapshotIndexIT extends AbstractMongoIT {

    private static final List<String> TRAVERSAL = List.of("release_id", "parent_id");
    private static final List<String> IDENTITY = List.of("release_id", "node_id");

    private List<Document> snapshotIndexes() {
        List<Document> all = new ArrayList<>();
        db.getCollection("taxonomy_snapshot_nodes").listIndexes().into(all);
        return all;
    }

    private List<Document> matching(List<String> keys) {
        return snapshotIndexes().stream()
                .filter(ix -> ix.get("key", Document.class) != null
                        && new ArrayList<>(ix.get("key", Document.class).keySet()).equals(keys))
                .toList();
    }

    @Test
    void a_fresh_bootstrap_creates_the_release_parent_traversal_index() {
        db.getCollection("taxonomy_snapshot_nodes").dropIndexes();
        schemaBootstrap.bootstrap(db);

        List<Document> ix = matching(TRAVERSAL);
        assertThat(ix).hasSize(1);
        Document key = ix.get(0).get("key", Document.class);
        assertThat(new ArrayList<>(key.keySet())).containsExactly("release_id", "parent_id");
        assertThat(((Number) key.get("release_id")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) key.get("parent_id")).doubleValue()).isEqualTo(1.0);
        assertThat(ix.get(0).getBoolean("unique", false))
                .as("traversal index is NOT unique — many children share a parent").isFalse();
    }

    @Test
    void bootstrap_rerun_is_idempotent_for_snapshot_indexes() {
        db.getCollection("taxonomy_snapshot_nodes").dropIndexes();
        schemaBootstrap.bootstrap(db);
        int afterFirst = snapshotIndexes().size();

        schemaBootstrap.bootstrap(db);
        schemaBootstrap.bootstrap(db);

        assertThat(snapshotIndexes()).hasSize(afterFirst);
        assertThat(matching(TRAVERSAL)).hasSize(1);
    }

    @Test
    void the_unique_release_node_identity_index_remains_intact() {
        db.getCollection("taxonomy_snapshot_nodes").dropIndexes();
        schemaBootstrap.bootstrap(db);

        List<Document> ix = matching(IDENTITY);
        assertThat(ix).as("additive: the identity index is untouched").hasSize(1);
        assertThat(ix.get(0).getBoolean("unique", false)).isTrue();
        assertThat(matching(TRAVERSAL)).as("both coexist by design").hasSize(1);
    }
}
