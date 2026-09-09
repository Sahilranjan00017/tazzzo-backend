package com.tazzzo.catalog;

import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3B — the release-bound topology reader. Every test here is a DIVERGENCE test: the live
 * tree is deliberately changed after release R1 is snapshotted, then a second release R2 is
 * snapshotted, so that a reader which quietly fell back to live data, or leaked across releases,
 * would return the wrong answer.
 *
 * <p>Fixture history:
 * <pre>
 *   seed v0.9.0                          -> recordBaseline("R1")     [460 nodes frozen in R1]
 *   open R2
 *     rename  TZV-000001  "Basmati Rice" -> "Basmati Rice RENAMED"
 *     move    TZV-000001  TZG-000001     -> TZG-000002
 *     split   TZV-000057  (Salt)         -> two NEW vertical ids
 *   activateRelease("R2")                                             [live tree frozen in R2]
 * </pre>
 */
class SnapshotTaxonomyReaderIT extends AbstractMongoIT {

    private static final String R1 = "R1";
    private static final String R2 = "R2";
    private static final String BASMATI = "TZV-000001";
    private static final String OLD_PARENT = "TZG-000001";
    private static final String NEW_PARENT = "TZG-000002";
    private static final String STAPLES = "TZS-000001";
    private static final String SALT = "TZV-000057";

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService changes;
    @Autowired SnapshotTaxonomyReader reader;

    private List<String> minted;

    private int liveVersion(String nodeId) {
        return db.getCollection("taxonomy_nodes").find(eq("_id", nodeId)).first().getInteger("version");
    }

    private Document live(String nodeId) {
        return db.getCollection("taxonomy_nodes").find(eq("_id", nodeId)).first();
    }

    @BeforeAll
    void seedTwoDivergentReleases() {
        loader.load(db);
        changes.recordBaseline(R1);

        changes.openRelease(R2, R1);
        changes.renameNode(BASMATI, liveVersion(BASMATI), "Basmati Rice RENAMED");
        changes.moveNode(BASMATI, liveVersion(BASMATI), NEW_PARENT);
        minted = changes.splitNode(SALT, liveVersion(SALT), List.of("Salt Split X", "Salt Split Y"));
        changes.activateRelease(R2);

        // Precondition for every divergence test below: the live tree really did move on.
        assertThat(live(BASMATI).getString("name")).isEqualTo("Basmati Rice RENAMED");
        assertThat(live(BASMATI).getString("parent_id")).isEqualTo(NEW_PARENT);
        assertThat(minted).hasSize(2);
        // A rogue snapshot row under a release nobody asked for, pointing INTO R1's tree by
        // parent_id. A reader that filtered on parent_id alone would pick it up.
        db.getCollection("taxonomy_snapshot_nodes").insertOne(new Document("release_id", "RX")
                .append("node_id", "TZV-ROGUE").append("node_type", "vertical")
                .append("parent_id", OLD_PARENT).append("name", "Rogue").append("status", "active"));
    }

    // ---------- A. live rename / reparent does not change the old snapshot ----------

    @Test
    void A_live_rename_and_reparent_leave_the_old_snapshot_untouched() {
        Document r1 = reader.node(R1, BASMATI);
        assertThat(r1.getString("name")).isEqualTo("Basmati Rice");
        assertThat(r1.getString("parent_id")).isEqualTo(OLD_PARENT);

        // and the live tree is provably different, so this is not a vacuous read
        assertThat(live(BASMATI).getString("name")).isEqualTo("Basmati Rice RENAMED");
        assertThat(live(BASMATI).getString("parent_id")).isEqualTo(NEW_PARENT);
    }

    // ---------- B. a node that exists only in a later topology is absent from the older one ----------

    @Test
    void B_a_node_minted_after_R1_is_absent_from_R1_and_present_in_R2() {
        for (String id : minted) {
            assertThat(live(id)).as("minted node exists live").isNotNull();
            assertThat(reader.node(R1, id))
                    .as("R1 was frozen before " + id + " existed — absent, no live fallback")
                    .isNull();
            assertThat(reader.node(R2, id)).isNotNull();
        }
        assertThat(reader.verticalIdsInSubtree(R1, SALT))
                .as("in R1, Salt is still an undivided vertical")
                .containsExactly(SALT);
    }

    // ---------- C. subtree traversal never crosses release_id ----------

    @Test
    void C_subtree_traversal_never_crosses_release_id() {
        assertThat(reader.verticalIdsInSubtree(R1, OLD_PARENT))
                .contains(BASMATI)
                .doesNotContain("TZV-ROGUE");
        assertThat(reader.immediateChildren(R1, OLD_PARENT))
                .extracting(d -> d.getString("node_id"))
                .doesNotContain("TZV-ROGUE");
        assertThat(reader.verticalIdsInSubtree(R2, OLD_PARENT))
                .as("R2 recorded the move away from this parent")
                .doesNotContain(BASMATI);
        assertThat(reader.verticalIdsInSubtree(R2, NEW_PARENT)).contains(BASMATI);
        assertThat(reader.verticalIdsInSubtree(R1, NEW_PARENT)).doesNotContain(BASMATI);
    }

    // ---------- D. same node_id, different parentage per release ----------

    @Test
    void D_each_release_returns_its_own_parentage_for_the_same_node_id() {
        assertThat(reader.node(R1, BASMATI).getString("parent_id")).isEqualTo(OLD_PARENT);
        assertThat(reader.node(R2, BASMATI).getString("parent_id")).isEqualTo(NEW_PARENT);

        assertThat(reader.immediateChildren(R1, OLD_PARENT))
                .extracting(d -> d.getString("node_id")).contains(BASMATI);
        assertThat(reader.immediateChildren(R2, OLD_PARENT))
                .extracting(d -> d.getString("node_id")).doesNotContain(BASMATI);
        assertThat(reader.immediateChildren(R2, NEW_PARENT))
                .extracting(d -> d.getString("node_id")).contains(BASMATI);
    }

    // ---------- E. vertical node types only ----------

    @Test
    void E_verticalIdsInSubtree_returns_vertical_node_types_only() {
        List<String> ids = reader.verticalIdsInSubtree(R1, STAPLES);
        assertThat(ids).as("Staples descendant verticals in the frozen seed").hasSize(69);
        for (String id : ids) {
            assertThat(reader.node(R1, id).getString("node_type")).isEqualTo("vertical");
        }
        assertThat(ids).noneMatch(id -> id.startsWith("TZC-") || id.startsWith("TZG-"));
    }

    // ---------- F. empty stays empty — no live fallback ----------

    @Test
    void F_an_empty_snapshot_result_stays_empty() {
        assertThat(live(STAPLES)).as("the node exists LIVE").isNotNull();
        assertThat(reader.node("R-NONE", STAPLES))
                .as("a release with no snapshot yields ABSENT even for a node the live tree has")
                .isNull();
        assertThat(reader.verticalIdsInSubtree("R-NONE", STAPLES)).isEmpty();
        assertThat(reader.immediateChildren("R-NONE", STAPLES)).isEmpty();

        assertThat(reader.node(R1, "TZV-DOES-NOT-EXIST")).isNull();
        assertThat(reader.verticalIdsInSubtree(R1, "TZV-DOES-NOT-EXIST")).isEmpty();
        assertThat(reader.immediateChildren(R1, "TZV-DOES-NOT-EXIST")).isEmpty();
    }

    // ---------- G. a vertical scope resolves to itself ----------

    @Test
    void G_a_vertical_resolves_to_itself_in_its_snapshot() {
        assertThat(reader.verticalIdsInSubtree(R1, BASMATI)).containsExactly(BASMATI);
        assertThat(reader.verticalIdsInSubtree(R2, BASMATI)).containsExactly(BASMATI);
    }

    // ---------- the snapshot's own fields, not live ones ----------

    @Test
    void snapshot_fields_are_the_recorded_values_not_todays() {
        // The seed carries branch_status/origin on this vertical; the snapshot copied them.
        Document r1 = reader.node(R1, BASMATI);
        assertThat(r1.getString("node_type")).isEqualTo("vertical");
        assertThat(r1.getString("status")).isEqualTo("active");
        assertThat(r1.getString("release_id")).isEqualTo(R1);
        assertThat(r1.getString("node_id")).isEqualTo(BASMATI);
    }

    @Test
    void a_concrete_release_is_required_on_every_call() {
        assertThatThrownBy(() -> reader.node(null, BASMATI)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.node(" ", BASMATI)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.immediateChildren(null, STAPLES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.verticalIdsInSubtree(null, STAPLES)).isInstanceOf(IllegalArgumentException.class);
    }
}
