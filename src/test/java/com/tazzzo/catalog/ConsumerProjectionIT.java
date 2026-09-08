package com.tazzzo.catalog;

import com.tazzzo.catalog.consumer.ConsumerAttributeResponse;
import com.tazzzo.catalog.consumer.ConsumerProductResponse;
import com.tazzzo.catalog.consumer.ConsumerProjectionService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 — RESP-PROJ projection. Default deny at key AND value level, with RP-3 and RP-5 enforced
 * in the projector rather than trusted to policy authorship: every prohibition test below puts the
 * forbidden key IN the policy, because that is the only way to prove the projector refuses it.
 */
class ConsumerProjectionIT extends AbstractMongoIT {

    private static final String REL = "rel-1.0";
    private static final String V = "TZV-000001";

    @Autowired private ConsumerProjectionService projection;

    @BeforeEach
    void reset() {
        db.getCollection("products").deleteMany(new Document());
        db.getCollection("consumer_projection_policy").deleteMany(new Document());
        db.getCollection("attribute_definitions").deleteMany(new Document());
        definition("grain_length", "enum_open", "descriptive", List.of("long", "medium", "short"), null);
        definition("aged", "boolean", "descriptive", null, null);
        definition("brand", "string", "descriptive", null, null);
        definition("pack_size", "number", "descriptive", null, "pack_unit");
        definition("pack_unit", "enum_open", "descriptive", List.of("kg", "g", "pieces"), null);
        definition("organic_certified", "boolean", "claim", null, null);
        definition("form", "enum_open", "descriptive", List.of("whole", "split"), null);
    }

    // ---------- fixtures ----------

    private void definition(String key, String type, String governance,
                            List<String> knownValues, String pairedUnit) {
        Document d = new Document("key", key).append("version", 1).append("type", type)
                .append("governance", governance).append("status", "active");
        if (knownValues != null) d.append("known_values", knownValues);
        if (pairedUnit != null) d.append("paired_unit", pairedUnit);
        db.getCollection("attribute_definitions").insertOne(d);
    }

    private Document product(String id, Map<String, Object> attributes) {
        Document p = new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "BR").append("title", "Title " + id)
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", V)
                        .append("release_id", REL).append("status", "confirmed"))
                .append("attributes", new Document(attributes))
                .append("attributes_meta", new Document("validated_release", REL))
                .append("version", 1).append("created_at", new Date());
        db.getCollection("products").insertOne(p);
        return db.getCollection("products").find(new Document("_id", id)).first();
    }

    /** Each entry is key:order:label. */
    private void policy(String version, String... entries) {
        List<Document> attrs = new ArrayList<>();
        for (String e : entries) {
            String[] parts = e.split(":");
            attrs.add(new Document("attribute_key", parts[0])
                    .append("display_order", Integer.parseInt(parts[1]))
                    .append("display_label", parts[2]));
        }
        db.getCollection("consumer_projection_policy").insertOne(
                new Document("vertical_id", V).append("projection_version", version)
                        .append("attributes", attrs));
    }

    private List<String> keys(ConsumerProductResponse r) {
        return r.attributes().stream().map(ConsumerAttributeResponse::key).toList();
    }

    // ---------- RP-6b: absence is a successful state ----------

    @Test
    void no_policy_yields_empty_attributes_and_a_null_version() {
        Document p = product("TZP-P1", Map.of("grain_length", "long"));
        ConsumerProductResponse r = projection.project(p);

        assertThat(r.attributes()).isEmpty();
        assertThat(r.projectionVersion())
                .as("null, never a synthesized 0 — unauthored must differ from authored-and-empty")
                .isNull();
        assertThat(r.id()).isEqualTo("TZP-P1");
        assertThat(r.title()).isEqualTo("Title TZP-P1");
    }

    // ---------- RP-2 key level ----------

    @Test
    void only_keys_the_policy_opts_into_are_projected() {
        policy("v3", "grain_length:1:Grain length");
        Document p = product("TZP-P2", Map.of("grain_length", "long", "aged", true));
        ConsumerProductResponse r = projection.project(p);

        assertThat(keys(r)).containsExactly("grain_length");
        assertThat(r.projectionVersion()).isEqualTo("v3");
    }

    @Test
    void display_order_determines_the_sequence() {
        policy("v3", "aged:2:Aged", "grain_length:1:Grain length", "brand:3:Brand");
        Document p = product("TZP-P3", Map.of("grain_length", "long", "aged", true, "brand", "X"));

        assertThat(keys(projection.project(p)))
                .containsExactly("grain_length", "aged", "brand");
    }

    // ---------- RP-2 value level ----------

    @Test
    void an_enum_open_value_outside_the_ratified_vocabulary_is_not_published() {
        policy("v3", "grain_length:1:Grain length");
        Document accepted = product("TZP-P4", Map.of("grain_length", "extra-long-supplier-text"));

        assertThat(keys(projection.project(accepted)))
                .as("H-11 accepted it into the catalogue; publication is a separate question")
                .isEmpty();

        Document known = product("TZP-P5", Map.of("grain_length", "medium"));
        assertThat(keys(projection.project(known))).containsExactly("grain_length");
    }

    @Test
    void an_attribute_with_no_active_definition_is_not_published() {
        policy("v3", "undefined_key:1:Undefined");
        Document p = product("TZP-P6", Map.of("undefined_key", "whatever"));

        assertThat(keys(projection.project(p)))
                .as("unverifiable, therefore denied")
                .isEmpty();
    }

    // ---------- RP-3 / RP-5: prohibitions ABOVE the configuration ----------

    @Test
    void a_claim_attribute_is_refused_even_when_the_policy_lists_it() {
        policy("v3", "organic_certified:1:Organic", "aged:2:Aged");
        Document p = product("TZP-P7", Map.of("organic_certified", true, "aged", true));

        assertThat(keys(projection.project(p)))
                .as("RP-3 is enforced in the projector, not trusted to policy authorship")
                .containsExactly("aged");
    }

    @Test
    void form_is_refused_even_when_the_policy_lists_it() {
        policy("v3", "form:1:Form", "aged:2:Aged");
        Document p = product("TZP-P8", Map.of("form", "whole", "aged", true));

        assertThat(keys(projection.project(p)))
                .as("RP-5 — `form` stays unpublished while T-3 is open")
                .containsExactly("aged");
    }

    // ---------- RP-4: quantity is atomic ----------

    @Test
    void pack_size_and_pack_unit_render_as_one_item_carrying_the_unit() {
        policy("v3", "pack_size:1:Pack size");
        Document p = product("TZP-P9", Map.of("pack_size", 5, "pack_unit", "kg"));
        ConsumerProductResponse r = projection.project(p);

        assertThat(r.attributes()).hasSize(1);
        ConsumerAttributeResponse a = r.attributes().get(0);
        assertThat(a.key()).isEqualTo("pack_size");
        assertThat(a.value()).isEqualTo(5);
        assertThat(a.unit()).isEqualTo("kg");
    }

    @Test
    void a_bare_pack_size_is_omitted_entirely() {
        policy("v3", "pack_size:1:Pack size", "aged:2:Aged");
        Document p = product("TZP-PA", Map.of("pack_size", 5, "aged", true));

        assertThat(keys(projection.project(p)))
                .as("never emit a bare quantity, never infer the unit")
                .containsExactly("aged");
    }

    @Test
    void a_unit_is_never_projected_as_a_standalone_item() {
        policy("v3", "pack_unit:1:Unit", "aged:2:Aged");
        Document p = product("TZP-PB", Map.of("pack_size", 5, "pack_unit", "kg", "aged", true));

        assertThat(keys(projection.project(p)))
                .as("pack_unit is the unit OF pack_size, not an attribute of its own")
                .containsExactly("aged");
    }

    @Test
    void an_unpublishable_unit_omits_the_whole_quantity() {
        policy("v3", "pack_size:1:Pack size");
        Document p = product("TZP-PC", Map.of("pack_size", 5, "pack_unit", "furlongs"));

        assertThat(keys(projection.project(p)))
                .as("an unratified unit value cannot be rescued by projecting the number alone")
                .isEmpty();
    }

    // ---------- labels ----------

    @Test
    void the_policy_label_is_used_and_falls_back_to_the_key() {
        db.getCollection("consumer_projection_policy").insertOne(
                new Document("vertical_id", V).append("projection_version", "v4")
                        .append("attributes", List.of(
                                new Document("attribute_key", "aged").append("display_order", 1))));
        Document p = product("TZP-PD", Map.of("aged", true));

        assertThat(projection.project(p).attributes().get(0).label()).isEqualTo("aged");
    }
}
