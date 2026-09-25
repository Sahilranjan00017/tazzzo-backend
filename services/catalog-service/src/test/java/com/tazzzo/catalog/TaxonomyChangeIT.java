package com.tazzzo.catalog;

import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.schema.TaxonomyService;
import com.tazzzo.catalog.tx.CasConflictException;
import com.tazzzo.catalog.tx.ClassifyService;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.TaxonomyChangeException;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Step-1 suite: every CTO scenario, every rejection asserting its CODE (T-RULE-1).
 * The frozen v0.9.0 baseline is recorded first; all changes flow through release 1.0.0.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaxonomyChangeIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyService taxonomy;
    @Autowired TaxonomyChangeService changes;
    @Autowired MintService mintService;
    @Autowired ClassifyService classifyService;

    static final String BASMATI = "TZV-000001";           // Staples > Rice & Grains > Basmati Rice
    static final String BASMATI_SUB = "TZG-000001";

    private void expectCode(String code, Runnable op) {
        TaxonomyChangeException ex = catchThrowableOfType(op::run, TaxonomyChangeException.class);
        assertThat(ex).as("expected TaxonomyChangeException " + code).isNotNull();
        assertThat(ex.code).as("rejection must carry code (T-RULE-1)").isEqualTo(code);
    }

    private Document node(String id) {
        return db.getCollection("taxonomy_nodes").find(eq("_id", id)).first();
    }

    @BeforeAll
    void seedAndBaseline() {
        loader.load(db);
        changes.recordBaseline("0.9.0");
        // a product classified under the baseline, in a vertical we will merge away
        // (TZV-000002 is a real seeded rice vertical -> strict governance applies)
        mintService.mint(new com.tazzzo.catalog.domain.ProductDraft("TZP-TX1", "single",
                "internal", "txm|1", null, "BR-TEST", "Merge-victim product",
                "TZV-000002", "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "kg"), List.of(), null));
        classifyService.classify("TZP-TX1", "TZV-000002", "0.9.0", "confirmed", 0.99, List.of());
    }

    @Test @Order(1)
    void baseline_recorded_and_frozen_tree_untouched() {
        assertThat(db.getCollection("taxonomy_snapshot_nodes").countDocuments(eq("release_id", "0.9.0")))
                .isEqualTo(460);
        assertThat(db.getCollection("catalogue_releases").find(eq("_id", "0.9.0")).first()
                .getString("status")).isEqualTo("active");
    }

    @Test @Order(2)
    void change_without_open_release_fails_closed() {
        expectCode("NO_OPEN_RELEASE", () -> changes.renameNode(BASMATI, 1, "Nope"));
        assertThat(node(BASMATI).getString("name")).isEqualTo("Basmati Rice"); // untouched
    }

    @Test @Order(3)
    void open_release_then_second_open_rejected() {
        changes.openRelease("1.0.0", "0.9.0");
        expectCode("RELEASE_ALREADY_OPEN", () -> changes.openRelease("1.1.0", "0.9.0"));
    }

    @Test @Order(4)
    void rename_node_with_ledger_row() {
        changes.renameNode(BASMATI, 1, "Basmati Rice (Premium Grades)");
        assertThat(node(BASMATI).getString("name")).isEqualTo("Basmati Rice (Premium Grades)");
        Document ev = db.getCollection("node_events")
                .find(and(eq("node_id", BASMATI), eq("event", "renamed"))).first();
        assertThat(ev).isNotNull();
        assertThat(ev.get("detail", Document.class).getString("from")).isEqualTo("Basmati Rice");
        assertThat(ev.getString("release_id")).isEqualTo("1.0.0");
    }

    @Test @Order(5)
    void concurrent_modification_stale_cas_rejected() {
        // version is now 2 after the rename; a caller still holding version 1 must lose
        assertThatThrownBy(() -> changes.renameNode(BASMATI, 1, "Stale Writer"))
                .isInstanceOf(CasConflictException.class);
        assertThat(node(BASMATI).getString("name")).isEqualTo("Basmati Rice (Premium Grades)");
    }

    @Test @Order(6)
    void move_rules_invalid_parent_level_cycle_and_missing_parent() {
        expectCode("INVALID_PARENT_LEVEL", () -> changes.moveNode(BASMATI, 2, "TZS-000001"));
        expectCode("CYCLE", () -> changes.moveNode(BASMATI, 2, BASMATI)); // self-parent
        expectCode("INVALID_PARENT", () -> changes.moveNode(BASMATI, 2, "TZG-999999"));
        // legal move: vertical to another sub_category
        changes.moveNode(BASMATI, 2, "TZG-000002");
        assertThat(node(BASMATI).getString("parent_id")).isEqualTo("TZG-000002");
        assertThat(db.getCollection("node_events")
                .find(and(eq("node_id", BASMATI), eq("event", "re_parented"))).first()).isNotNull();
        // move it back for later tests
        changes.moveNode(BASMATI, 3, BASMATI_SUB);
    }

    @Test @Order(7)
    void merge_schema_conflict_fails_closed_then_reconciled_merge_succeeds() {
        // TZV-000002 (rice schema) into a vertical with a different schema id
        Document other = db.getCollection("taxonomy_nodes").find(and(eq("node_type", "vertical"),
                new Document("attribute_schema_id", new Document("$ne", "rice")),
                eq("status", "active"))).first();
        expectCode("SCHEMA_CONFLICT", () -> changes.mergeNodes("TZV-000002", 1,
                other.getString("_id"), false));
        assertThat(node("TZV-000002").getString("status")).isEqualTo("active"); // untouched
        // reconciled merge into a same-schema sibling (Basmati)
        changes.mergeNodes("TZV-000002", 1, BASMATI, true);
        Document loser = node("TZV-000002");
        assertThat(loser.getString("status")).isEqualTo("merged");
        assertThat(loser.getString("merged_into")).isEqualTo(BASMATI);
    }

    @Test @Order(8)
    void merge_stamps_products_and_history_stays_on_original_release() {
        changes.runStampWorker(50); // M4: fan-out happens in the chunked worker
        Document item = db.getCollection("work_queue")
                .find(eq("_id", "reclassification:TZP-TX1:1.0.0")).first();
        assertThat(item).as("affected product stamped with reclassification item").isNotNull();
        // product still carries its decided-at release + vertical (historical attribution)
        Document p = db.getCollection("products").find(eq("_id", "TZP-TX1")).first();
        assertThat(p.get("classification", Document.class).getString("release_id")).isEqualTo("0.9.0");
        assertThat(p.get("classification", Document.class).getString("vertical_id")).isEqualTo("TZV-000002");
        // now reclassify through T4 under the new release
        classifyService.classify("TZP-TX1", BASMATI, "1.0.0", "confirmed", 0.99, List.of());
        List<Document> hist = db.getCollection("classification_history")
                .find(eq("product_id", "TZP-TX1")).into(new java.util.ArrayList<>());
        assertThat(hist).hasSizeGreaterThanOrEqualTo(3); // mint + 0.9.0 decision + 1.0.0 decision
        assertThat(hist.stream().anyMatch(h -> "0.9.0".equals(h.getString("release_id"))
                && "TZV-000002".equals(h.getString("vertical_id"))))
                .as("old classification remains attributable to its original release").isTrue();
    }

    @Test @Order(9)
    void merge_repoints_aliases_to_survivor() {
        db.getCollection("aliases").insertOne(new Document("alias_norm", "old rice name")
                .append("lang", "xx").append("region", "all")
                .append("node_id", "TZV-000003").append("status", "active"));
        changes.mergeNodes("TZV-000003", 1, BASMATI, true);
        assertThat(db.getCollection("aliases").find(eq("alias_norm", "old rice name")).first()
                .getString("node_id")).isEqualTo(BASMATI);
    }

    @Test @Order(10)
    void split_mints_new_ids_never_reuses_and_rejects_duplicates() {
        expectCode("INVALID_SPLIT", () -> changes.splitNode("TZV-000004", 1, List.of("Only One")));
        // "Ponni Rice" IS an active sibling of TZV-000004 (same sub-category TZG-000002)
        expectCode("DUPLICATE_NODE", () -> changes.splitNode("TZV-000004", 1,
                List.of("Ponni Rice", "Something Else")));
        List<String> minted = changes.splitNode("TZV-000004", 1,
                List.of("Split Child A", "Split Child B"));
        assertThat(minted).hasSize(2);
        assertThat(minted).allMatch(id -> id.compareTo("TZV-100000") > 0); // fresh id space
        assertThat(node("TZV-000004").getString("status")).isEqualTo("deprecated");
        for (String id : minted) {
            assertThat(node(id).getString("status")).isEqualTo("active");
            assertThat(node(id).getString("parent_id")).isEqualTo(node("TZV-000004").getString("parent_id"));
        }
        // deprecated/merged nodes reject further changes
        expectCode("NODE_NOT_ACTIVE", () -> changes.renameNode("TZV-000004", 2, "Zombie"));
    }

    @Test @Order(11)
    void release_activation_crash_is_resumable_and_idempotent() {
        changes.activateRelease("1.0.0", 100, 2); // crash after 2 batches (~200 of 462 nodes)
        assertThat(db.getCollection("catalogue_releases").find(eq("_id", "1.0.0")).first()
                .getString("status")).as("crashed activation stays FROZEN (fail-closed)")
                .isEqualTo("freezing");
        // the frozen tree rejects changes while the snapshot is incomplete (M3)
        expectCode("NO_OPEN_RELEASE", () -> changes.renameNode(BASMATI, 4, "Blocked"));
        long partial = db.getCollection("taxonomy_snapshot_nodes").countDocuments(eq("release_id", "1.0.0"));
        assertThat(partial).isGreaterThan(0).isLessThan(460);
        changes.activateRelease("1.0.0"); // resume
        assertThat(db.getCollection("catalogue_releases").find(eq("_id", "1.0.0")).first()
                .getString("status")).isEqualTo("active");
        long full = db.getCollection("taxonomy_snapshot_nodes").countDocuments(eq("release_id", "1.0.0"));
        assertThat(full).isEqualTo(db.getCollection("taxonomy_nodes").countDocuments());
        // idempotent re-run of the snapshot phase must change nothing
        assertThatThrownBy(() -> changes.activateRelease("1.0.0"))
                .isInstanceOf(TaxonomyChangeException.class)
                .hasMessageContaining("RELEASE_NOT_OPEN");
    }

    @Test @Order(12)
    void snapshot_reconstruction_baseline_vs_new_release() {
        Document base = db.getCollection("taxonomy_snapshot_nodes")
                .find(and(eq("release_id", "0.9.0"), eq("node_id", BASMATI))).first();
        Document rel1 = db.getCollection("taxonomy_snapshot_nodes")
                .find(and(eq("release_id", "1.0.0"), eq("node_id", BASMATI))).first();
        assertThat(base.getString("name")).isEqualTo("Basmati Rice");           // history intact
        assertThat(rel1.getString("name")).isEqualTo("Basmati Rice (Premium Grades)");
        Document mergedInBase = db.getCollection("taxonomy_snapshot_nodes")
                .find(and(eq("release_id", "0.9.0"), eq("node_id", "TZV-000002"))).first();
        assertThat(mergedInBase.getString("status")).isEqualTo("active");       // was active then
    }

    @Test @Order(13)
    void extra_codes_deprecate_flow_and_guards() {
        changes.openRelease("1.1.0", "1.0.0");
        expectCode("NODE_NOT_FOUND", () -> changes.renameNode("TZV-999998", 1, "Ghost"));
        expectCode("INVALID_MERGE", () -> changes.mergeNodes(BASMATI, 4, BASMATI, true));
        // rename onto an ACTIVE sibling's name is rejected (review m-finding guard):
        // "Biryani Basmati Rice"... was TZV-000002 (merged). Use a live sibling under TZG-000001.
        Document sibling = db.getCollection("taxonomy_nodes").find(and(
                eq("parent_id", BASMATI_SUB), eq("status", "active"),
                new Document("_id", new Document("$ne", BASMATI)))).first();
        if (sibling != null) {
            expectCode("DUPLICATE_NODE", () -> changes.renameNode(BASMATI, 4, sibling.getString("name")));
        }
        // deprecate guard: a sub-category with active verticals refuses deprecation
        expectCode("HAS_ACTIVE_CHILDREN", () -> changes.deprecateNode("TZG-000002", 1));
        // a leaf vertical deprecates cleanly, and further changes are refused
        Document leaf = db.getCollection("taxonomy_nodes").find(and(
                eq("node_type", "vertical"), eq("status", "active"),
                eq("parent_id", "TZG-000003"))).first();
        changes.deprecateNode(leaf.getString("_id"), leaf.getInteger("version"));
        expectCode("NODE_NOT_ACTIVE", () -> changes.renameNode(leaf.getString("_id"),
                leaf.getInteger("version") + 1, "Zombie"));
        changes.activateRelease("1.1.0");
    }

    @Test @Order(14)
    void frozen_baseline_still_fully_reconstructable_after_all_changes() {
        assertThat(db.getCollection("taxonomy_snapshot_nodes").countDocuments(eq("release_id", "0.9.0")))
                .isEqualTo(460);
        // and the live tree still resolves paths (post-change)
        assertThat(taxonomy.renderPath(BASMATI)).contains("Staples").contains("Premium Grades");
    }
}
