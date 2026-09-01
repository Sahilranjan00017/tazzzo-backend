package com.tazzzo.catalog;

import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.MintService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * U-4-f — catalogue acceptance is not identity resolution.
 *
 * A SKU with no stated quantity (loose atta sold by weight at a counter) used to be REJECTED at
 * mint, while the same situation in meat/fish/egg was accepted — an inconsistency, not a
 * catalogue rule. It is now catalogued, unidentified, and queued.
 *
 * The three-layer contract these tests pin:
 *   catalogue acceptance  — fails only when the record cannot be formed at all
 *   identity resolution   — fails when the ratified evidence is absent
 *   canonical convergence — happens when two sources derive the same key
 */
class QuantityOptionalIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader taxonomyLoader;
    @Autowired MintService mintService;

    static final String V = "TZV-000026"; // Atta & Wheat Flour — a schema that REQUIRED pack

    @BeforeAll
    void load() { taxonomyLoader.load(db); }

    @Test
    void u4f_1_a_quantityless_sku_is_catalogued_not_rejected() {
        assertThatCode(() -> mintService.mint(new ProductDraft("TZP-U4F-1", "single", "internal",
                "u4f|1", null, "BR-LOOSE", "Loose atta, sold by weight", V, "0.9.0",
                "provisional", Map.of(), List.of(), null)))
                .as("missing quantity is a data-completeness problem, not a creation failure")
                .doesNotThrowAnyException();

        Document p = db.getCollection("products").find(eq("_id", "TZP-U4F-1")).first();
        assertThat(p).isNotNull();
        assertThat(p.getString("title")).isEqualTo("Loose atta, sold by weight");
    }

    @Test
    void u4f_2_identity_stays_unresolved_and_is_never_guessed() {
        mintService.mint(new ProductDraft("TZP-U4F-2", "single", "internal", "u4f|2", null,
                "BR-LOOSE", "Loose atta 2", V, "0.9.0", "provisional", Map.of(), List.of(), null));

        Document p = db.getCollection("products").find(eq("_id", "TZP-U4F-2")).first();
        assertThat(p.get("identity", Document.class).getString("canonical_key"))
                .as("no quantity, no pack term, no key — and no inferred default")
                .isNull();
        assertThat(db.getCollection("canonical_keys").countDocuments(eq("product_id", "TZP-U4F-2")))
                .isZero();
        assertThat(db.getCollection("work_queue")
                .countDocuments(eq("_id", "identity_incomplete:TZP-U4F-2"))).isEqualTo(1);
    }

    @Test
    void u4f_3_the_missing_quantity_is_queued_not_silently_lost() {
        mintService.mint(new ProductDraft("TZP-U4F-3", "single", "internal", "u4f|3", null,
                "BR-LOOSE", "Loose atta 3", V, "0.9.0", "provisional", Map.of(), List.of(), null));

        Document item = db.getCollection("work_queue")
                .find(eq("_id", "attribute_incomplete:" + V)).first();
        assertThat(item).as("Law 4: fail-closed to a queue, never silent").isNotNull();
        assertThat(item.getString("missing")).isEqualTo("pack_size/pack_unit");
    }

    /**
     * Uses a DIFFERENT vertical from the tests above: attribute_incomplete is keyed by VERTICAL,
     * not by product, following the existing validation_gap precedent — validate() is given a
     * vertical and an attribute map, never a product id. That makes it a vertical-level signal
     * ("something here lacks quantity"); the per-product trace already exists as
     * identity_incomplete:{productId}, asserted in u4f_2. The two are complementary, and the
     * aggregate shape is friendlier to Q-1 queue volume.
     */
    @Test
    void u4f_4_a_quantity_bearing_sku_raises_no_gap() {
        String clean = "TZV-000027"; // Maida & Refined Flour, untouched by the tests above
        mintService.mint(new ProductDraft("TZP-U4F-4", "single", "internal", "u4f|4", null,
                "BR-LOOSE", "Packed maida 5kg", clean, "0.9.0", "provisional",
                Map.of("pack_size", 5, "pack_unit", "kg"), List.of(), null));
        assertThat(db.getCollection("work_queue")
                .countDocuments(eq("_id", "attribute_incomplete:" + clean))).isZero();
    }
}
