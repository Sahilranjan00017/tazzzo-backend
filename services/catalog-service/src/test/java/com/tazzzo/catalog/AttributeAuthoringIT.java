package com.tazzzo.catalog;

import com.tazzzo.catalog.domain.ProductDraft;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.AttributeAuthoringService;
import com.tazzzo.catalog.tx.AttributeViolationException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Step-2 suite: the 10 CTO proofs + AT-2, every rejection asserting its code (T-RULE-1). */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AttributeAuthoringIT extends AbstractMongoIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired AttributeAuthoringService authoring;
    @Autowired MintService mintService;

    static final String BASMATI = "TZV-000001"; // rice schema

    private void expectCode(String code, Runnable op) {
        TaxonomyChangeException ex = catchThrowableOfType(op::run, TaxonomyChangeException.class);
        assertThat(ex).as("expected TaxonomyChangeException " + code).isNotNull();
        assertThat(ex.code).isEqualTo(code);
    }

    private ProductDraft riceDraft(String id, String idKey, Map<String, Object> attrs) {
        return new ProductDraft(id, "single", "internal", idKey, null, "BR-TEST",
                "Rice " + id, BASMATI, "0.9.0", "provisional", attrs, List.of(), null);
    }

    @BeforeAll
    void seedAndBaseline() {
        loader.load(db);
        releases.recordBaseline("0.9.0");
        mintService.mint(riceDraft("TZP-AT2-OLD", "at2|old", Map.of("pack_size", 5, "pack_unit", "kg")));
    }

    @Test @Order(1)
    void authoring_requires_open_release_and_rejects_bad_inputs() {
        expectCode("NO_OPEN_RELEASE", () -> authoring.createDefinition("moisture_pct", "number", "descriptive", null));
        releases.openRelease("2.0.0", "0.9.0");
        expectCode("INVALID_TYPE", () -> authoring.createDefinition("bad", "blob", "descriptive", null));
        expectCode("MERCHANDISING_REFUSED", () -> authoring.createDefinition("trending", "boolean", "merchandising", null));
    }

    @Test @Order(2)
    void pending_definition_is_invisible_until_release_activates() {
        int v = authoring.createDefinition("moisture_pct", "number", "descriptive", null);
        assertThat(v).isEqualTo(1);
        int sv = authoring.addSchemaField("rice", "moisture_pct", false, false);
        assertThat(sv).isEqualTo(2);
        // schema v2 is PENDING: strict governance still uses rice@1 -> unknown key rejected
        assertThatThrownBy(() -> mintService.mint(riceDraft("TZP-AT2-EARLY", "at2|early",
                Map.of("pack_size", 1, "pack_unit", "kg", "moisture_pct", 11))))
                .isInstanceOf(AttributeViolationException.class)
                .hasMessageContaining("moisture_pct");
    }

    @Test @Order(3)
    void at2_acceptance_new_attribute_end_to_end_with_zero_structural_drift() {
        String collectionsBefore = collectionMeta();
        String productIndexesBefore = indexMeta("products");
        String validatorBefore = validatorOf("products");
        Document oldProductBefore = db.getCollection("products").find(eq("_id", "TZP-AT2-OLD")).first();

        releases.activateRelease("2.0.0");   // atomic flip: defs+schemas pending -> active

        assertThat(collectionMeta()).as("no collection created/modified").isEqualTo(collectionsBefore);
        assertThat(indexMeta("products")).as("no index change").isEqualTo(productIndexesBefore);
        assertThat(validatorOf("products")).as("no collMod on products validator").isEqualTo(validatorBefore);
        assertThat(db.getCollection("products").find(eq("_id", "TZP-AT2-OLD")).first())
                .as("existing product document byte-identical").isEqualTo(oldProductBefore);

        // new products can now use the attribute
        mintService.mint(riceDraft("TZP-AT2-NEW", "at2|new",
                Map.of("pack_size", 1, "pack_unit", "kg", "moisture_pct", 11)));
        assertThat(db.getCollection("products").find(eq("_id", "TZP-AT2-NEW")).first()
                .get("attributes", Document.class).getInteger("moisture_pct")).isEqualTo(11);
    }

    @Test @Order(4)
    void enum_value_addition_is_additive_data_only() {
        // pack_unit known_values gains "quintal" — no release, no schema change
        String validatorBefore = validatorOf("products");
        authoring.addEnumValue("pack_unit", "quintal");
        Document def = db.getCollection("attribute_definitions")
                .find(and(eq("key", "pack_unit"), eq("version", 1))).first();
        assertThat(def.getList("known_values", String.class)).contains("quintal");
        assertThat(validatorOf("products")).isEqualTo(validatorBefore);
        // a product using it now raises NO unknown-value work item
        mintService.mint(riceDraft("TZP-AT2-Q", "at2|q", Map.of("pack_size", 1, "pack_unit", "quintal")));
        assertThat(db.getCollection("work_queue")
                .find(eq("_id", "attr_unknown:pack_unit:quintal")).first()).isNull();
        expectCode("ENUM_ONLY", () -> authoring.addEnumValue("moisture_pct", "wet"));
        expectCode("UNKNOWN_DEFINITION", () -> authoring.addEnumValue("no_such_key", "x"));
    }

    @Test @Order(5)
    void required_field_needs_explicit_breaking_acknowledgment_then_stamps_products() {
        releases.openRelease("2.1.0", "2.0.0");
        authoring.createDefinition("origin_state", "string", "descriptive", null);
        expectCode("REQUIRED_NEEDS_BACKFILL",
                () -> authoring.addSchemaField("rice", "origin_state", true, false));
        authoring.addSchemaField("rice", "origin_state", true, true); // acknowledged breaking
        releases.activateRelease("2.1.0");
        releases.runStampWorker(100);
        // every product in every rice vertical is stamped for revalidation
        assertThat(db.getCollection("work_queue")
                .find(eq("_id", "attribute_revalidation:TZP-AT2-OLD:2.1.0")).first())
                .as("existing product stamped, not silently invalidated").isNotNull();
        // the old product still carries its original validation provenance (proof 10)
        assertThat(db.getCollection("products").find(eq("_id", "TZP-AT2-OLD")).first()
                .get("attributes_meta", Document.class).getString("validated_release"))
                .isEqualTo("0.9.0");
        // NEW products must now provide the required field
        assertThatThrownBy(() -> mintService.mint(riceDraft("TZP-AT2-R1", "at2|r1",
                Map.of("pack_size", 1, "pack_unit", "kg"))))
                .isInstanceOf(AttributeViolationException.class)
                .hasMessageContaining("origin_state");
        mintService.mint(riceDraft("TZP-AT2-R2", "at2|r2",
                Map.of("pack_size", 1, "pack_unit", "kg", "origin_state", "Haryana")));
    }

    @Test @Order(6)
    void type_change_is_forbidden_new_semantic_key_required() {
        releases.openRelease("2.2.0", "2.1.0");
        expectCode("TYPE_CHANGE_FORBIDDEN",
                () -> authoring.createDefinition("moisture_pct", "string", "descriptive", null));
    }

    @Test @Order(7)
    void claim_tier_attribute_requires_evidence_through_the_full_path() {
        authoring.createDefinition("lab_tested", "boolean", "claim", null);
        authoring.addSchemaField("rice", "lab_tested", false, false);
        releases.activateRelease("2.2.0");
        assertThatThrownBy(() -> mintService.mint(riceDraft("TZP-AT2-C1", "at2|c1",
                Map.of("pack_size", 1, "pack_unit", "kg", "origin_state", "Punjab", "lab_tested", true))))
                .isInstanceOf(AttributeViolationException.class)
                .hasMessageContaining("lab_tested");
        mintService.mint(new ProductDraft("TZP-AT2-C2", "single", "internal", "at2|c2", null,
                "BR-TEST", "Claim ok", BASMATI, "0.9.0", "provisional",
                Map.of("pack_size", 1, "pack_unit", "kg", "origin_state", "Punjab", "lab_tested", true),
                List.of("EV-AT2"), null));
    }

    @Test @Order(8)
    void historical_versions_are_immutable() {
        Document defV1 = db.getCollection("attribute_definitions")
                .find(and(eq("key", "moisture_pct"), eq("version", 1))).first();
        assertThat(defV1.getString("status")).isEqualTo("active");
        Document schemaV1 = db.getCollection("attribute_schemas")
                .find(and(eq("schema_id", "rice"), eq("version", 1))).first();
        assertThat(schemaV1.getString("status")).isEqualTo("superseded");
        assertThat(schemaV1.getList("fields", Document.class).stream()
                .noneMatch(f -> "moisture_pct".equals(f.getString("key"))))
                .as("v1 fields never retro-edited").isTrue();
        List<Document> riceVersions = db.getCollection("attribute_schemas")
                .find(eq("schema_id", "rice")).into(new ArrayList<>());
        assertThat(riceVersions).hasSize(4); // 1 seed + 2.0.0 + 2.1.0 + 2.2.0
    }

    @Test @Order(9)
    void crash_during_activation_leaves_no_partially_active_schema() {
        releases.openRelease("2.3.0", "2.2.0");
        authoring.createDefinition("aroma_grade", "string", "descriptive", null);
        authoring.addSchemaField("rice", "aroma_grade", false, false);
        releases.activateRelease("2.3.0", 100, 1); // crash mid-snapshot, BEFORE the flip txn
        assertThat(db.getCollection("catalogue_releases").find(eq("_id", "2.3.0")).first()
                .getString("status")).isEqualTo("freezing");
        assertThat(db.getCollection("attribute_definitions")
                .find(and(eq("key", "aroma_grade"), eq("version", 1))).first().getString("status"))
                .as("definition still pending after crash — nothing partially active")
                .isEqualTo("pending");
        // and changes are fail-closed while frozen
        expectCode("NO_OPEN_RELEASE",
                () -> authoring.createDefinition("blocked_key", "string", "descriptive", null));
        releases.activateRelease("2.3.0"); // resume
        assertThat(db.getCollection("attribute_definitions")
                .find(and(eq("key", "aroma_grade"), eq("version", 1))).first().getString("status"))
                .isEqualTo("active");
    }

    @Test @Order(10)
    void multi_field_per_release_and_remaining_codes() {
        releases.openRelease("2.5.0", "2.4.0");
        authoring.createDefinition("field_a", "string", "descriptive", null);
        authoring.createDefinition("field_b", "string", "descriptive", null);
        int v1 = authoring.addSchemaField("rice", "field_a", false, false);
        int v2 = authoring.addSchemaField("rice", "field_b", false, false); // amends same pending draft
        assertThat(v2).as("second field amends the SAME pending version").isEqualTo(v1);
        expectCode("DUPLICATE_FIELD", () -> authoring.addSchemaField("rice", "field_a", false, false));
        expectCode("UNKNOWN_SCHEMA", () -> authoring.addSchemaField("no_such_schema", "field_a", false, false));
        expectCode("INVALID_GOVERNANCE", () -> authoring.createDefinition("g", "string", "whatever", null));
        // enum carry-forward across a version bump (H-11 monotonicity)
        int pv = authoring.createDefinition("pack_unit", "enum_open", "descriptive", List.of("tonne"));
        releases.activateRelease("2.5.0");
        Document newDef = db.getCollection("attribute_definitions")
                .find(and(eq("key", "pack_unit"), eq("version", pv))).first();
        assertThat(newDef.getList("known_values", String.class))
                .as("version bump carries known_values forward and unions new ones")
                .contains("quintal", "kg", "tonne");
        Document oldDef = db.getCollection("attribute_definitions")
                .find(and(eq("key", "pack_unit"), eq("version", 1))).first();
        assertThat(oldDef.getString("status")).isEqualTo("superseded");
    }

    @Test @Order(11)
    void stamp_worker_rerun_is_idempotent_and_gate_allows_next_release() {
        long items = db.getCollection("work_queue").countDocuments(eq("type", "attribute_revalidation"));
        releases.runStampWorker(100);
        assertThat(db.getCollection("work_queue").countDocuments(eq("type", "attribute_revalidation")))
                .isEqualTo(items);
        releases.openRelease("2.4.0", "2.3.0"); // gate cleared by activation — next release opens
        releases.activateRelease("2.4.0");
    }

    // ---- metadata capture (AT-1/AT-2 style) ----
    private String collectionMeta() {
        List<String> metas = new ArrayList<>();
        for (Document c : client.getDatabase("tazzzo_it").listCollections()) {
            c.remove("info");
            metas.add(c.toJson()); // full metadata, same strength as AT-1
        }
        metas.sort(String::compareTo);
        return String.join("|", metas);
    }

    private String indexMeta(String coll) {
        List<String> metas = new ArrayList<>();
        db.getCollection(coll).listIndexes().forEach(i -> metas.add(i.toJson()));
        metas.sort(String::compareTo);
        return String.join("|", metas);
    }

    private String validatorOf(String coll) {
        for (Document c : client.getDatabase("tazzzo_it").listCollections()) {
            if (coll.equals(c.getString("name"))) {
                Document opts = c.get("options", Document.class);
                return opts == null ? "" : opts.toJson();
            }
        }
        return "";
    }
}
