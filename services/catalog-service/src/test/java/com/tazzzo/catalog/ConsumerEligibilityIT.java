package com.tazzzo.catalog;

import com.tazzzo.catalog.consumer.ConsumerEligibility;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 — the shared consumer-eligibility predicate, exercised against real product documents
 * under the live products validator. Every fixture below is inserted through the validator, so a
 * fixture that could not exist in the catalogue cannot silently prove anything.
 */
class ConsumerEligibilityIT extends AbstractMongoIT {

    private static final String REL = "rel-1.0";
    private static final String V_RICE = "TZV-000001";
    private static final String V_DAL = "TZV-000010";

    @BeforeEach
    void clearProducts() {
        db.getCollection("products").deleteMany(new Document());
    }

    // ---------- fixtures ----------

    private Document base(String id, String type, String vertical, String lifecycle, String status) {
        Document classification = new Document("vertical_id", vertical)
                .append("release_id", REL).append("status", status);
        return new Document("_id", id).append("product_type", type)
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "BR").append("title", "t " + id)
                .append("lifecycle", lifecycle).append("classification", classification)
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", REL))
                .append("version", 1).append("created_at", new Date());
    }

    private void insert(Document d) {
        db.getCollection("products").insertOne(d);
    }

    private void single(String id, String vertical, String lifecycle, String status) {
        insert(base(id, "single", vertical, lifecycle, status));
    }

    private void variantPack(String id, String vertical) {
        insert(base(id, "variant_pack", vertical, "active", "confirmed")
                .append("pack_of", new Document("component_product_id", "TZP-C1").append("qty", 4)));
    }

    private void bundle(String id) {
        insert(base(id, "bundle", null, "active", "confirmed")
                .append("bundle_contents", List.of(
                        new Document("component_product_id", "TZP-C1").append("qty", 1),
                        new Document("component_product_id", "TZP-C2").append("qty", 1))));
    }

    private Set<String> selected() {
        List<String> ids = new ArrayList<>();
        db.getCollection("products").find(ConsumerEligibility.filter())
                .projection(new Document("_id", 1)).forEach(d -> ids.add(d.getString("_id")));
        return Set.copyOf(ids);
    }

    private Set<String> selectedWithin(java.util.Collection<String> verticals) {
        List<String> ids = new ArrayList<>();
        db.getCollection("products").find(ConsumerEligibility.within(verticals))
                .projection(new Document("_id", 1)).forEach(d -> ids.add(d.getString("_id")));
        return Set.copyOf(ids);
    }

    // ---------- the four axes ----------

    @Test
    void only_active_lifecycle_is_eligible() {
        single("TZP-A1", V_RICE, "active", "confirmed");
        single("TZP-A2", V_RICE, "draft", "confirmed");
        single("TZP-A3", V_RICE, "discontinued", "confirmed");
        single("TZP-A4", V_RICE, "archived", "confirmed");
        single("TZP-A5", V_RICE, "merged", "confirmed");
        single("TZP-A6", V_RICE, "merging", "confirmed");
        assertThat(selected()).containsExactly("TZP-A1");
    }

    @Test
    void only_confirmed_classification_is_eligible() {
        single("TZP-B1", V_RICE, "active", "confirmed");
        single("TZP-B2", V_RICE, "active", "provisional");
        single("TZP-B3", V_RICE, "active", "review");
        single("TZP-B4", V_RICE, "active", "scope_blocked");
        assertThat(selected()).containsExactly("TZP-B1");
    }

    @Test
    void holding_verticals_are_never_eligible() {
        single("TZP-C1", V_RICE, "active", "confirmed");
        single("TZP-C2", "TZV-UNCLASSIFIED", "active", "confirmed");
        single("TZP-C3", "TZV-SCOPE-BLOCKED", "active", "confirmed");
        assertThat(selected()).containsExactly("TZP-C1");
    }

    /** LIST-ELIG-1 — single and variant_pack are admitted; bundle is not. */
    @Test
    void product_type_admits_single_and_variant_pack_only() {
        single("TZP-D1", V_RICE, "active", "confirmed");
        variantPack("TZP-D2", V_RICE);
        bundle("TZP-D3");
        assertThat(selected()).containsExactlyInAnyOrder("TZP-D1", "TZP-D2");
    }

    // ---------- scoping ----------

    @Test
    void within_scopes_to_the_supplied_vertical_set() {
        single("TZP-E1", V_RICE, "active", "confirmed");
        single("TZP-E2", V_DAL, "active", "confirmed");
        assertThat(selectedWithin(List.of(V_RICE))).containsExactly("TZP-E1");
        assertThat(selectedWithin(List.of(V_RICE, V_DAL)))
                .containsExactlyInAnyOrder("TZP-E1", "TZP-E2");
    }

    /** An empty descendant set matches nothing — no consumer-visible descendant, no products. */
    @Test
    void within_an_empty_vertical_set_matches_nothing() {
        single("TZP-F1", V_RICE, "active", "confirmed");
        assertThat(selectedWithin(List.of())).isEmpty();
    }

    /** Law 4: the holding exclusion still applies even if a caller passes one in. */
    @Test
    void within_still_excludes_a_holding_vertical_passed_by_the_caller() {
        single("TZP-G1", "TZV-UNCLASSIFIED", "active", "confirmed");
        assertThat(selectedWithin(List.of("TZV-UNCLASSIFIED", V_RICE))).isEmpty();
    }

    // ---------- in-memory form agrees with the query form ----------

    @Test
    void isEligible_agrees_with_the_query_on_every_fixture() {
        single("TZP-H1", V_RICE, "active", "confirmed");
        single("TZP-H2", V_RICE, "draft", "confirmed");
        single("TZP-H3", V_RICE, "active", "provisional");
        single("TZP-H4", "TZV-UNCLASSIFIED", "active", "confirmed");
        variantPack("TZP-H5", V_RICE);
        bundle("TZP-H6");

        Set<String> viaQuery = selected();
        List<String> viaMemory = new ArrayList<>();
        db.getCollection("products").find().forEach(d -> {
            if (ConsumerEligibility.isEligible(d)) viaMemory.add(d.getString("_id"));
        });
        assertThat(Set.copyOf(viaMemory))
                .as("the in-memory predicate must not be a second definition of truth")
                .isEqualTo(viaQuery);
        assertThat(viaQuery).containsExactlyInAnyOrder("TZP-H1", "TZP-H5");
    }

    // ---------- REL-MEM-1 / R-A: the release id has NO membership effect ----------

    /**
     * The most dangerous available regression: a future reader sees a field called
     * {@code classification.release_id} and filters on it, silently reverting R-A and making
     * membership historical instead of current. This test fails if that happens.
     */
    @Test
    void classification_release_id_has_no_membership_effect() {
        Document older = base("TZP-R1", "single", V_RICE, "active", "confirmed");
        older.get("classification", Document.class).append("release_id", "rel-0.9");
        insert(older);

        Document newer = base("TZP-R2", "single", V_RICE, "active", "confirmed");
        newer.get("classification", Document.class).append("release_id", "rel-2.0");
        insert(newer);

        assertThat(selected())
                .as("R-A: the resolved release scopes the TAXONOMY, never product membership")
                .containsExactlyInAnyOrder("TZP-R1", "TZP-R2");
        assertThat(selectedWithin(List.of(V_RICE)))
                .containsExactlyInAnyOrder("TZP-R1", "TZP-R2");

        db.getCollection("products").find().forEach(d ->
                assertThat(ConsumerEligibility.isEligible(d))
                        .as("in-memory form must agree: " + d.getString("_id"))
                        .isTrue());
    }

    @Test
    void isEligible_is_null_safe() {
        assertThat(ConsumerEligibility.isEligible(null)).isFalse();
        assertThat(ConsumerEligibility.isEligible(new Document())).isFalse();
    }
}
