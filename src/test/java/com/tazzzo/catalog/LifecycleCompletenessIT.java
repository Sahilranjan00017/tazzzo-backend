package com.tazzzo.catalog;

import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.*;
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

/** Closes the Catalogue-domain gap: full product lifecycle + taxonomy revive. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LifecycleCompletenessIT extends AbstractMongoIT {

    @Autowired MintService mintService;
    @Autowired ProductLifecycleService lifecycle;
    @Autowired MergeService mergeService;
    @Autowired TaxonomyChangeService changes;
    @Autowired TaxonomyLoader loader;

    static final String BASMATI = "TZV-000001";

    private int version(String id) {
        return db.getCollection("products").find(eq("_id", id)).first().getInteger("version");
    }

    private String state(String id) {
        return db.getCollection("products").find(eq("_id", id)).first().getString("lifecycle");
    }

    private void mint(String id, String key) {
        mintService.mint(new com.tazzzo.catalog.domain.ProductDraft(id, "single", "internal", key,
                null, "BR-LC", "Lifecycle " + id, BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "kg"), List.of(), null));
    }

    @BeforeAll
    void seed() {
        loader.load(db);
        changes.recordBaseline("0.9.0");
    }

    @Test @Order(1)
    void full_legal_path_draft_active_discontinued_revived_archived() {
        mint("TZP-LC1", "lc|1");
        assertThat(state("TZP-LC1")).isEqualTo("draft");
        lifecycle.activate("TZP-LC1", version("TZP-LC1"));
        assertThat(state("TZP-LC1")).isEqualTo("active");
        lifecycle.discontinue("TZP-LC1", version("TZP-LC1"), "supplier exit");
        assertThat(state("TZP-LC1")).isEqualTo("discontinued");
        lifecycle.revive("TZP-LC1", version("TZP-LC1"), null);
        assertThat(state("TZP-LC1")).isEqualTo("active");
        lifecycle.discontinue("TZP-LC1", version("TZP-LC1"), null);
        lifecycle.archive("TZP-LC1", version("TZP-LC1"));
        assertThat(state("TZP-LC1")).isEqualTo("archived");
        // every transition emitted an audit event
        assertThat(db.getCollection("product_events").countDocuments(and(
                eq("product_id", "TZP-LC1"), eq("type", "PRODUCT_ARCHIVED")))).isEqualTo(1);
        assertThat(db.getCollection("product_events").countDocuments(and(
                eq("product_id", "TZP-LC1"), eq("type", "PRODUCT_REVIVED")))).isEqualTo(1);
    }

    @Test @Order(2)
    void terminal_states_are_terminal_and_skips_are_refused() {
        // archived is terminal
        ProductStateException ex = catchThrowableOfType(
                () -> lifecycle.activate("TZP-LC1", version("TZP-LC1")), ProductStateException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("archived -> active");
        // draft cannot skip straight to discontinued/archived
        mint("TZP-LC2", "lc|2");
        assertThatThrownBy(() -> lifecycle.discontinue("TZP-LC2", version("TZP-LC2"), null))
                .isInstanceOf(ProductStateException.class)
                .hasMessageContaining("draft -> discontinued");
        assertThat(state("TZP-LC2")).isEqualTo("draft");
    }

    @Test @Order(3)
    void merging_products_cannot_be_lifecycle_edited() {
        mint("TZP-LC3", "lc|3");
        mint("TZP-LC4", "lc|4");
        lifecycle.activate("TZP-LC3", version("TZP-LC3"));
        lifecycle.activate("TZP-LC4", version("TZP-LC4"));
        mergeService.startMerge("TZP-LC3", "TZP-LC4");
        assertThat(state("TZP-LC3")).isEqualTo("merging");
        assertThatThrownBy(() -> lifecycle.discontinue("TZP-LC3", version("TZP-LC3"), null))
                .isInstanceOf(ProductStateException.class)
                .hasMessageContaining("merging");
        mergeService.runFinalizer();
        assertThat(state("TZP-LC3")).isEqualTo("merged");
        // merged is terminal too
        assertThatThrownBy(() -> lifecycle.activate("TZP-LC3", version("TZP-LC3")))
                .isInstanceOf(ProductStateException.class);
    }

    @Test @Order(4)
    void revive_refused_when_formulation_changed() {
        mint("TZP-LC5", "lc|5");
        lifecycle.activate("TZP-LC5", version("TZP-LC5"));
        lifecycle.discontinue("TZP-LC5", version("TZP-LC5"), null);
        db.getCollection("products").updateOne(eq("_id", "TZP-LC5"),
                new Document("$set", new Document("formulation_version", 1)));
        assertThatThrownBy(() -> lifecycle.revive("TZP-LC5", version("TZP-LC5"), 2))
                .isInstanceOf(ProductStateException.class)
                .hasMessageContaining("mint a NEW product");
        assertThat(state("TZP-LC5")).isEqualTo("discontinued");
    }

    @Test @Order(5)
    void taxonomy_deprecate_then_revive_round_trip() {
        changes.openRelease("9.0.0", "0.9.0");
        Document leaf = db.getCollection("taxonomy_nodes").find(and(
                eq("node_type", "vertical"), eq("status", "active"),
                eq("parent_id", "TZG-000004"))).first();
        String id = leaf.getString("_id");
        changes.deprecateNode(id, leaf.getInteger("version"));
        assertThat(node(id).getString("status")).isEqualTo("deprecated");
        changes.reviveNode(id, node(id).getInteger("version"));
        assertThat(node(id).getString("status")).isEqualTo("active");
        assertThat(db.getCollection("node_events").countDocuments(and(
                eq("node_id", id), eq("event", "revived")))).isEqualTo(1);
        // only deprecated nodes revive
        TaxonomyChangeException ex = catchThrowableOfType(
                () -> changes.reviveNode(id, node(id).getInteger("version")),
                TaxonomyChangeException.class);
        assertThat(ex.code).isEqualTo("NOT_DEPRECATED");
        changes.activateRelease("9.0.0");
    }

    private Document node(String id) {
        return db.getCollection("taxonomy_nodes").find(eq("_id", id)).first();
    }
}
