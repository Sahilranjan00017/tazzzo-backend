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

import com.tazzzo.catalog.consumer.ConsumerProjectionPolicyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 — RESP-PROJ projection. Default deny at key AND value level, with RP-3 and RP-5 enforced
 * in the projector rather than trusted to policy authorship: every prohibition test below puts the
 * forbidden key IN the policy, because that is the only way to prove the projector refuses it.
 */
class ConsumerProjectionIT extends AbstractMongoIT {

    private static final String REL = "rel-1.0";
    private static final String V = "TZV-000001";
    private static final String V_B = "TZV-000010";
    private static final String V_C = "TZV-000020";

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
        return product(id, V, attributes);
    }

    private Document product(String id, String vertical, Map<String, Object> attributes) {
        Document p = new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "BR").append("title", "Title " + id)
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", vertical)
                        .append("release_id", REL).append("status", "confirmed"))
                .append("attributes", new Document(attributes))
                .append("attributes_meta", new Document("validated_release", REL))
                .append("version", 1).append("created_at", new Date());
        db.getCollection("products").insertOne(p);
        return db.getCollection("products").find(new Document("_id", id)).first();
    }

    /** Each entry is key:order:label. */
    private void policy(String vertical, String version, String... entries) {
        List<Document> attrs = new ArrayList<>();
        for (String e : entries) {
            String[] parts = e.split(":");
            attrs.add(new Document("attribute_key", parts[0])
                    .append("display_order", Integer.parseInt(parts[1]))
                    .append("display_label", parts[2]));
        }
        db.getCollection("consumer_projection_policy").insertOne(
                new Document("vertical_id", vertical).append("projection_version", version)
                        .append("attributes", attrs));
    }

    /** Inserts a policy document verbatim, for the RP-6d malformed cases. */
    private void rawPolicy(Document doc) {
        db.getCollection("consumer_projection_policy").insertOne(doc);
    }

    private Document entry(String key, Integer order, String label) {
        Document d = new Document("attribute_key", key);
        if (order != null) d.append("display_order", order);
        if (label != null) d.append("display_label", label);
        return d;
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
        policy(V, "v3", "grain_length:1:Grain length");
        Document p = product("TZP-P2", Map.of("grain_length", "long", "aged", true));
        ConsumerProductResponse r = projection.project(p);

        assertThat(keys(r)).containsExactly("grain_length");
        assertThat(r.projectionVersion()).isEqualTo("v3");
    }

    @Test
    void display_order_determines_the_sequence() {
        policy(V, "v3", "aged:2:Aged", "grain_length:1:Grain length", "brand:3:Brand");
        Document p = product("TZP-P3", Map.of("grain_length", "long", "aged", true, "brand", "X"));

        assertThat(keys(projection.project(p)))
                .containsExactly("grain_length", "aged", "brand");
    }

    // ---------- RP-2 value level ----------

    @Test
    void an_enum_open_value_outside_the_ratified_vocabulary_is_not_published() {
        policy(V, "v3", "grain_length:1:Grain length");
        Document accepted = product("TZP-P4", Map.of("grain_length", "extra-long-supplier-text"));

        assertThat(keys(projection.project(accepted)))
                .as("H-11 accepted it into the catalogue; publication is a separate question")
                .isEmpty();

        Document known = product("TZP-P5", Map.of("grain_length", "medium"));
        assertThat(keys(projection.project(known))).containsExactly("grain_length");
    }

    @Test
    void an_attribute_with_no_active_definition_is_not_published() {
        policy(V, "v3", "undefined_key:1:Undefined");
        Document p = product("TZP-P6", Map.of("undefined_key", "whatever"));

        assertThat(keys(projection.project(p)))
                .as("unverifiable, therefore denied")
                .isEmpty();
    }

    // ---------- RP-3 / RP-5: prohibitions ABOVE the configuration ----------

    @Test
    void a_claim_attribute_is_refused_even_when_the_policy_lists_it() {
        policy(V, "v3", "organic_certified:1:Organic", "aged:2:Aged");
        Document p = product("TZP-P7", Map.of("organic_certified", true, "aged", true));

        assertThat(keys(projection.project(p)))
                .as("RP-3 is enforced in the projector, not trusted to policy authorship")
                .containsExactly("aged");
    }

    @Test
    void form_is_refused_even_when_the_policy_lists_it() {
        policy(V, "v3", "form:1:Form", "aged:2:Aged");
        Document p = product("TZP-P8", Map.of("form", "whole", "aged", true));

        assertThat(keys(projection.project(p)))
                .as("RP-5 — `form` stays unpublished while T-3 is open")
                .containsExactly("aged");
    }

    // ---------- RP-4: quantity is atomic ----------

    @Test
    void pack_size_and_pack_unit_render_as_one_item_carrying_the_unit() {
        policy(V, "v3", "pack_size:1:Pack size");
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
        policy(V, "v3", "pack_size:1:Pack size", "aged:2:Aged");
        Document p = product("TZP-PA", Map.of("pack_size", 5, "aged", true));

        assertThat(keys(projection.project(p)))
                .as("never emit a bare quantity, never infer the unit")
                .containsExactly("aged");
    }

    @Test
    void a_unit_is_never_projected_as_a_standalone_item() {
        policy(V, "v3", "pack_unit:1:Unit", "aged:2:Aged");
        Document p = product("TZP-PB", Map.of("pack_size", 5, "pack_unit", "kg", "aged", true));

        assertThat(keys(projection.project(p)))
                .as("pack_unit is the unit OF pack_size, not an attribute of its own")
                .containsExactly("aged");
    }

    @Test
    void an_unpublishable_unit_omits_the_whole_quantity() {
        policy(V, "v3", "pack_size:1:Pack size");
        Document p = product("TZP-PC", Map.of("pack_size", 5, "pack_unit", "furlongs"));

        assertThat(keys(projection.project(p)))
                .as("an unratified unit value cannot be rescued by projecting the number alone")
                .isEmpty();
    }

    // ---------- RP-6c: item-local version across MULTIPLE verticals ----------

    /** The amendment that motivated this segment: one page, three verticals, three versions. */
    @Test
    void projection_version_is_item_local_across_verticals() {
        policy(V, "v3", "aged:1:Aged");
        policy(V_B, "v7", "aged:1:Aged");
        // V_C deliberately has NO policy.
        Document a = product("TZP-M1", V, Map.of("aged", true));
        Document b = product("TZP-M2", V_B, Map.of("aged", true));
        Document c = product("TZP-M3", V_C, Map.of("aged", true));

        assertThat(projection.project(a).projectionVersion()).isEqualTo("v3");
        assertThat(projection.project(b).projectionVersion()).isEqualTo("v7");
        assertThat(projection.project(c).projectionVersion())
                .as("no policy for this vertical — null, and no envelope value could be truthful")
                .isNull();
        assertThat(keys(projection.project(c))).isEmpty();
    }

    /** Authored-but-empty is a REAL policy: empty attributes with a NON-null version. */
    @Test
    void an_authored_empty_policy_is_distinct_from_no_policy() {
        policy(V, "v9");
        Document p = product("TZP-M4", Map.of("aged", true));
        ConsumerProductResponse r = projection.project(p);

        assertThat(r.attributes()).isEmpty();
        assertThat(r.projectionVersion())
                .as("authored-and-empty must be distinguishable from unauthored")
                .isEqualTo("v9");
    }

    // ---------- RP-6d: a malformed policy is a CONFIGURATION FAILURE, never "no policy" ----------

    @Test
    void a_policy_without_a_projection_version_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V)
                .append("attributes", List.of(entry("aged", 1, "Aged"))));
        Document p = product("TZP-D1", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("projection_version");
    }

    @Test
    void a_blank_projection_version_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "  ")
                .append("attributes", List.of(entry("aged", 1, "Aged"))));
        Document p = product("TZP-D2", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class);
    }

    @Test
    void a_missing_display_order_is_a_configuration_failure_not_a_default() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(entry("aged", null, "Aged"))));
        Document p = product("TZP-D3", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("display_order");
    }

    @Test
    void a_missing_display_label_is_a_configuration_failure_not_a_key_fallback() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(entry("grain_length", 1, null))));
        Document p = product("TZP-D4", Map.of("grain_length", "long"));

        assertThatThrownBy(() -> projection.project(p))
                .as("a governed key is not a shopper label")
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("display_label");
    }

    @Test
    void a_blank_attribute_key_is_a_configuration_failure_not_a_silent_skip() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(entry("  ", 1, "Label"))));
        Document p = product("TZP-D5", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("attribute_key");
    }

    @Test
    void a_duplicate_attribute_key_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(entry("aged", 1, "Aged"), entry("aged", 2, "Aged again"))));
        Document p = product("TZP-D6", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("duplicate attribute_key");
    }

    @Test
    void a_duplicate_display_order_is_a_configuration_failure_not_an_alphabetical_tiebreak() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(entry("aged", 1, "Aged"), entry("brand", 1, "Brand"))));
        Document p = product("TZP-D7", Map.of("aged", true, "brand", "X"));

        assertThatThrownBy(() -> projection.project(p))
                .as("two entries claiming one display position is an authoring ambiguity")
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("duplicate display_order");
    }

    // ---------- RP-6d: STORED SHAPE, not just field presence ----------

    @Test
    void a_policy_with_no_attributes_array_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3"));
        Document p = product("TZP-S1", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .as("missing and empty are different authored states; normalising one into the"
                        + " other is the silent repair RP-6d removed")
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("no attributes array");
    }

    @Test
    void attributes_that_is_not_an_array_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", "not-an-array"));
        Document p = product("TZP-S2", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("not an array");
    }

    @Test
    void a_non_document_attributes_member_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of("not-a-document")));
        Document p = product("TZP-S3", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("not a document");
    }

    @Test
    void a_non_string_projection_version_is_a_configuration_failure_not_a_cast_error() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", 123)
                .append("attributes", List.of(entry("aged", 1, "Aged"))));
        Document p = product("TZP-S4", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .as("a wrong BSON type must arrive as the DEDICATED failure, not a driver cast")
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("projection_version is not a string");
    }

    @Test
    void a_non_integer_display_order_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(new Document("attribute_key", "aged")
                        .append("display_order", "first").append("display_label", "Aged"))));
        Document p = product("TZP-S5", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("display_order is not an integer");
    }

    @Test
    void a_display_order_outside_int_range_is_a_configuration_failure_not_an_arithmetic_error() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(new Document("attribute_key", "aged")
                        .append("display_order", Long.MAX_VALUE).append("display_label", "Aged"))));
        Document p = product("TZP-S7", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .as("a BSON Long is accepted by contract; only the CONVERSION failure is normalised")
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("outside the supported integer range");
    }

    @Test
    void a_non_string_display_label_is_a_configuration_failure() {
        rawPolicy(new Document("vertical_id", V).append("projection_version", "v3")
                .append("attributes", List.of(new Document("attribute_key", "aged")
                        .append("display_order", 1).append("display_label", 7))));
        Document p = product("TZP-S6", Map.of("aged", true));

        assertThatThrownBy(() -> projection.project(p))
                .isInstanceOf(ConsumerProjectionPolicyException.class)
                .hasMessageContaining("display_label is not a string");
    }

    // ---------- RP-2: the STORED VALUE must match its declared type ----------

    @Test
    void a_stored_value_that_contradicts_its_declared_type_is_not_published() {
        policy(V, "v3", "pack_size:1:Pack size", "aged:2:Aged");
        // pack_size is declared `number`; a directly-written string must not be published.
        Document p = product("TZP-T1", Map.of("pack_size", "five", "pack_unit", "kg", "aged", true));

        assertThat(keys(projection.project(p)))
                .as("a type NAME proves nothing about what is stored in a generic BSON object")
                .containsExactly("aged");
    }

    @Test
    void an_attribute_whose_definition_declares_an_unknown_type_is_not_published() {
        definition("weird_key", "geo_polygon", "descriptive", null, null);
        policy(V, "v3", "weird_key:1:Weird", "aged:2:Aged");
        Document p = product("TZP-T2", Map.of("weird_key", "anything", "aged", true));

        assertThat(keys(projection.project(p)))
                .as("the projector fails closed on a type it cannot reason about")
                .containsExactly("aged");
    }

    /** A superseded definition must not suppress a live attribute (active-only paired-unit check). */
    @Test
    void a_superseded_paired_unit_definition_does_not_suppress_an_attribute() {
        db.getCollection("attribute_definitions").insertOne(
                new Document("key", "legacy_size").append("version", 1).append("type", "number")
                        .append("governance", "descriptive").append("status", "superseded")
                        .append("paired_unit", "aged"));
        policy(V, "v3", "aged:1:Aged");
        Document p = product("TZP-T3", Map.of("aged", true));

        assertThat(keys(projection.project(p)))
                .as("only an ACTIVE definition may mark a key as somebody's unit")
                .containsExactly("aged");
    }
}
