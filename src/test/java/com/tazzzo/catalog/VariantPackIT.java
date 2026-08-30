package com.tazzzo.catalog;

import com.tazzzo.catalog.domain.PackOf;
import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.BundleService;
import com.tazzzo.catalog.tx.MintService;
import com.tazzzo.catalog.tx.VariantPackException;
import com.tazzzo.catalog.tx.VariantPackService;
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
 * F-5 — variant_pack is a real product type, not a decorative enum value.
 *
 * Before this slice, product_type="variant_pack" fell through ProductController to MintService
 * and was written as an ordinary single: a 6x250ml multipack was byte-identical to a 1500ml
 * bottle. These tests pin the three things that close that gap: pack_of is mandatory, the
 * component is checked live inside the transaction, and nesting is refused.
 */
class VariantPackIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader taxonomyLoader;
    @Autowired MintService mintService;
    @Autowired VariantPackService variantPackService;
    @Autowired BundleService bundleService;

    static final String NOODLES = "TZV-000001"; // any real seeded vertical; rice schema

    @BeforeAll
    void loadTaxonomy() {
        taxonomyLoader.load(db);
    }

    /** The component a multipack points at: one ordinary 70g single. */
    private void mintComponent(String id, String key) {
        mintService.mint(new ProductDraft(id, "single", "internal", key, null, "BR-TEST",
                "Component " + id, NOODLES, "0.9.0", "provisional",
                Map.of("pack_size", 70, "pack_unit", "g"), List.of(), null));
        bundleService.activate(id);
    }

    private ProductDraft pack(String id, String key, String componentId, int qty) {
        return new ProductDraft(id, "variant_pack", "internal", key, null, "BR-TEST",
                "Pack " + id, NOODLES, "0.9.0", "provisional",
                Map.of("pack_size", 70, "pack_unit", "g"), List.of(), null,
                new PackOf(componentId, qty));
    }

    @Test
    void f5_1_variant_pack_is_written_with_pack_of_and_is_distinct_from_the_component() {
        mintComponent("TZP-VP-C1", "vp|c1");
        variantPackService.writeVariantPack(pack("TZP-VP-P1", "vp|p1", "TZP-VP-C1", 8));

        Document p = db.getCollection("products").find(eq("_id", "TZP-VP-P1")).first();
        assertThat(p).isNotNull();
        assertThat(p.getString("product_type")).isEqualTo("variant_pack");
        Document packOf = p.get("pack_of", Document.class);
        assertThat(packOf).as("pack_of must be persisted — this is the field F-5 adds").isNotNull();
        assertThat(packOf.getString("component_product_id")).isEqualTo("TZP-VP-C1");
        assertThat(packOf.getInteger("qty")).isEqualTo(8);

        // the multipack and its component are two distinct catalogue products
        assertThat(db.getCollection("products").countDocuments(eq("_id", "TZP-VP-C1"))).isEqualTo(1);
        assertThat(p.getString("_id")).isNotEqualTo("TZP-VP-C1");
    }

    @Test
    void f5_2_variant_pack_without_pack_of_is_refused() {
        assertThatThrownBy(() -> variantPackService.writeVariantPack(
                new ProductDraft("TZP-VP-P2", "variant_pack", "internal", "vp|p2", null, "BR-TEST",
                        "No packOf", NOODLES, "0.9.0", "provisional",
                        Map.of("pack_size", 70, "pack_unit", "g"), List.of(), null)))
                .isInstanceOf(VariantPackException.class)
                .hasMessageContaining("requires pack_of");
        assertThat(db.getCollection("products").countDocuments(eq("_id", "TZP-VP-P2"))).isZero();
    }

    @Test
    void f5_3_qty_below_two_is_refused_a_pack_of_one_is_a_single() {
        mintComponent("TZP-VP-C3", "vp|c3");
        assertThatThrownBy(() -> variantPackService.writeVariantPack(
                pack("TZP-VP-P3", "vp|p3", "TZP-VP-C3", 1)))
                .isInstanceOf(VariantPackException.class)
                .hasMessageContaining("qty must be >= 2");
        assertThat(db.getCollection("products").countDocuments(eq("_id", "TZP-VP-P3"))).isZero();
    }

    @Test
    void f5_4_component_must_exist_and_be_active() {
        assertThatThrownBy(() -> variantPackService.writeVariantPack(
                pack("TZP-VP-P4", "vp|p4", "TZP-VP-MISSING", 6)))
                .isInstanceOf(VariantPackException.class)
                .hasMessageContaining("not active");

        // exists but still draft — never activated
        mintService.mint(new ProductDraft("TZP-VP-C5", "single", "internal", "vp|c5", null,
                "BR-TEST", "Draft component", NOODLES, "0.9.0", "provisional",
                Map.of("pack_size", 70, "pack_unit", "g"), List.of(), null));
        assertThatThrownBy(() -> variantPackService.writeVariantPack(
                pack("TZP-VP-P5", "vp|p5", "TZP-VP-C5", 6)))
                .isInstanceOf(VariantPackException.class)
                .hasMessageContaining("not active");
        assertThat(db.getCollection("products").countDocuments(eq("_id", "TZP-VP-P5"))).isZero();
    }

    @Test
    void f5_5_no_nesting_a_variant_pack_may_not_pack_another_variant_pack() {
        mintComponent("TZP-VP-C6", "vp|c6");
        variantPackService.writeVariantPack(pack("TZP-VP-P6", "vp|p6", "TZP-VP-C6", 6));
        bundleService.activate("TZP-VP-P6");

        assertThatThrownBy(() -> variantPackService.writeVariantPack(
                pack("TZP-VP-P7", "vp|p7", "TZP-VP-P6", 2)))
                .isInstanceOf(VariantPackException.class)
                .hasMessageContaining("no nesting");
        assertThat(db.getCollection("products").countDocuments(eq("_id", "TZP-VP-P7"))).isZero();
    }

    @Test
    void f5_6_validator_refuses_a_variant_pack_document_with_no_pack_of() {
        // the service is one guard; the validator is the independent second one (C-3 discipline)
        Document raw = new Document("_id", "TZP-VP-RAW").append("product_type", "variant_pack")
                .append("identity", new Document("type", "internal").append("internal_key", "vp|raw"))
                .append("brand_code", "BR-TEST").append("title", "Raw")
                .append("lifecycle", "draft")
                .append("classification", new Document("vertical_id", NOODLES)
                        .append("release_id", "0.9.0").append("status", "provisional")
                        .append("method_detail", new Document()).append("evidence_refs", List.of()))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "0.9.0"))
                .append("version", 1).append("created_at", new java.util.Date());
        assertThatThrownBy(() -> db.getCollection("products").insertOne(raw))
                .hasMessageContaining("Document failed validation");
    }
}
