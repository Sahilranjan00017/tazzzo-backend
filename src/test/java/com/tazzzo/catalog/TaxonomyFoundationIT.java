package com.tazzzo.catalog;

import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.schema.TaxonomyService;
import com.tazzzo.catalog.tx.AttributeViolationException;
import com.tazzzo.catalog.tx.MintService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The catalogue FOUNDATION, executed: frozen taxonomy v0.9.0 loaded into MongoDB, paths
 * resolvable, aliases live, and the attribute-governance enforcement path (service tier)
 * closing exactly the hole the I-6 bypass test proves exists at the validator tier.
 */
class TaxonomyFoundationIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader taxonomyLoader;
    @Autowired TaxonomyService taxonomyService;
    @Autowired MintService mintService;

    static final String BASMATI = "TZV-000001"; // minted in the v0.9.0 seed

    @BeforeAll
    void loadTaxonomy() {
        TaxonomyLoader.LoadResult r = taxonomyLoader.load(db);
        assertThat(r.nodes()).isEqualTo(460);
    }

    @Test
    void taxonomy_counts_match_frozen_master() {
        assertThat(db.getCollection("taxonomy_nodes").countDocuments(eq("node_type", "super_category"))).isEqualTo(7);
        assertThat(db.getCollection("taxonomy_nodes").countDocuments(eq("node_type", "category"))).isEqualTo(50);
        assertThat(db.getCollection("taxonomy_nodes").countDocuments(eq("node_type", "sub_category"))).isEqualTo(108);
        assertThat(db.getCollection("taxonomy_nodes").countDocuments(eq("node_type", "vertical"))).isEqualTo(295); // 293 + 2 holding
    }

    @Test
    void path_resolves_root_first() {
        assertThat(taxonomyService.renderPath(BASMATI))
                .isEqualTo("Staples > Rice & Grains > Basmati Rice > Basmati Rice");
        List<Document> path = taxonomyService.path(BASMATI);
        assertThat(path).hasSize(4);
        assertThat(path.get(0).getString("node_type")).isEqualTo("super_category");
        assertThat(path.get(3).getString("attribute_schema_id")).isEqualTo("rice");
    }

    @Test
    void alias_resolves_to_canonical_node() {
        Document node = taxonomyService.resolveAlias("Sonamasuri");
        assertThat(node).isNotNull();
        assertThat(node.getString("name")).isEqualTo("Sona Masoori Rice");
    }

    @Test
    void mint_against_real_vertical_valid_attributes_passes() {
        mintService.mint(new ProductDraft("TZP-F1", "single", "internal", "found|1", null,
                "BR-TEST", "India Gate Basmati 5kg", BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 5, "pack_unit", "kg", "variety_grade", "1121", "origin", "India"),
                List.of("EV-000001"), null));
        assertThat(db.getCollection("products").find(eq("_id", "TZP-F1")).first()).isNotNull();
    }

    @Test
    void i6_closure_fake_taxonomy_key_now_rejected_at_service_tier() {
        assertThatThrownBy(() -> mintService.mint(new ProductDraft("TZP-F2", "single", "internal",
                "found|2", null, "BR-TEST", "Bad", BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "kg", "fake_taxonomy_category", "whatever"),
                List.of(), null)))
                .isInstanceOf(AttributeViolationException.class)
                .hasMessageContaining("fake_taxonomy_category");
        assertThat(db.getCollection("products").find(eq("_id", "TZP-F2")).first())
                .as("nothing written on governance rejection").isNull();
    }

    @Test
    void required_attribute_enforced() {
        assertThatThrownBy(() -> mintService.mint(new ProductDraft("TZP-F3", "single", "internal",
                "found|3", null, "BR-TEST", "No pack", BASMATI, "0.9.0", "provisional",
                Map.of("variety_grade", "1121"), List.of(), null)))
                .isInstanceOf(AttributeViolationException.class)
                .hasMessageContaining("pack_size");
    }

    @Test
    void claim_tier_attribute_requires_evidence() {
        assertThatThrownBy(() -> mintService.mint(new ProductDraft("TZP-F4", "single", "internal",
                "found|4", null, "BR-TEST", "Organic claim", BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "kg", "organic_certified", true),
                List.of(), null)))
                .isInstanceOf(AttributeViolationException.class)
                .hasMessageContaining("organic_certified");
        // with evidence it passes
        mintService.mint(new ProductDraft("TZP-F5", "single", "internal", "found|5", null,
                "BR-TEST", "Organic ok", BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "kg", "organic_certified", true),
                List.of("EV-000009"), null));
    }

    @Test
    void unknown_enum_value_accepted_and_flagged() {
        mintService.mint(new ProductDraft("TZP-F6", "single", "internal", "found|6", null,
                "BR-TEST", "New unit", BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "quintal"), List.of(), null));
        assertThat(db.getCollection("work_queue")
                .find(eq("_id", "attr_unknown:pack_unit:quintal")).first())
                .as("unknown enum VALUE = data + work item, never a rejection").isNotNull();
    }

    @Test
    void lenient_path_for_unknown_vertical_emits_validation_gap() {
        mintService.mint(new ProductDraft("TZP-F7", "single", "internal", "found|7", null,
                "BR-TEST", "Fixture vertical", "TZV-999999", "0.9.0", "provisional",
                Map.of("pack_size", 1), List.of(), null));
        assertThat(db.getCollection("work_queue").find(eq("_id", "validation_gap:TZV-999999")).first())
                .isNotNull();
    }
}
