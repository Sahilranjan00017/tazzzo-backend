package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.MintService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.or;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-1 — attribute governance on HOLDING verticals.
 *
 * The hole, stated exactly: {@code AttributeGovernanceService.validate()} branches
 * {@code if (schema != null) … else if (vertical == null) …}. A holding vertical is IN
 * taxonomy_nodes with {@code attribute_schema_id: null}, so {@code vertical != null} AND
 * {@code schema == null} — neither branch runs. A key absent from attribute_definitions then
 * hits {@code def == null → continue} and is stored with no work item.
 *
 * The blast radius is exactly that and no wider: the second loop (type checks, enum_open
 * flagging, claim-tier evidence gate) sits OUTSIDE the if/else and still runs. Tests 4 and 5
 * exist to prove that, so the fix is not credited with closing a hole that was never open.
 *
 * Required disposition is taken from the CONTRACT, not from the implementation:
 *   CR-11 (Tazzzo_Canonical_Taxonomy_V1.md) — a holding vertical is REAL; the pipeline never
 *     stalls. So: not a rejection.
 *   Law 4 (Tazzzo_Catalogue_Contract_V3.md) + DB Readiness scenario 20 — no disposition rule
 *     means fail closed INTO A WORK ITEM, never silently. So: not silence.
 * Therefore: accept the product, emit the work item.
 */
class HoldingVerticalGovernanceIT extends AbstractApiIT {

    @Autowired TaxonomyLoader loader;

    /** A real, taxonomy-resident vertical carrying schema "rice" — the strict path. */
    private static final String RICE = "TZV-000001";
    private static final String RELEASE = "0.9.0";

    /** Absent from all 110 seeded attribute_definitions — asserted, not assumed, below. */
    private static final String BOGUS_KEY = "totally_made_up_key";

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
    }

    // ------------------------------------------------------------------ preconditions

    @Test
    void preconditions_the_defect_depends_on() {
        assertThat(db.getCollection("attribute_definitions").countDocuments(eq("key", BOGUS_KEY)))
                .as("the probe key must be genuinely unregistered, or tests 1-3 prove nothing")
                .isZero();
        for (String holding : List.of(MintService.UNCLASSIFIED, MintService.SCOPE_BLOCKED)) {
            Document n = db.getCollection("taxonomy_nodes").find(eq("_id", holding)).first();
            assertThat(n).as("%s must be resident in taxonomy_nodes", holding).isNotNull();
            assertThat(n.getString("node_type")).isEqualTo("vertical");
            assertThat(n.getString("attribute_schema_id"))
                    .as("%s is a vertical WITH NO SCHEMA — the third case the two-branch "
                            + "validate() never enumerated", holding)
                    .isNull();
        }
    }

    // ------------------------------------------------------------------ 1 + 2: the hole

    @Test
    void unregistered_key_on_unclassified_is_accepted_and_queued() {
        assertAcceptedAndQueued("TZP-F1-A", MintService.UNCLASSIFIED, "review");
    }

    @Test
    void unregistered_key_on_scope_blocked_is_accepted_and_queued() {
        assertAcceptedAndQueued("TZP-F1-B", MintService.SCOPE_BLOCKED, "scope_blocked");
    }

    private void assertAcceptedAndQueued(String id, String holdingVertical, String status) {
        ResponseEntity<JsonNode> res = post("/api/v1/products",
                draft(id, holdingVertical, status, Map.of(BOGUS_KEY, "xyz")),
                CMS_TOKEN, JsonNode.class);

        // CR-11: the pipeline never stalls. The holding vertical exists so unclassifiable
        // goods can ENTER. Rejecting here would defeat the node's whole purpose.
        assertThat(res.getStatusCode())
                .as("holding-vertical product must still be accepted; body=%s", res.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(db.getCollection("products").find(eq("_id", id)).first()).isNotNull();

        // Law 4 + Readiness scenario 20: and it must not be silent.
        Document item = db.getCollection("work_queue")
                .find(eq("_id", "validation_gap:" + holdingVertical)).first();
        assertThat(item)
                .as("ungoverned attributes on %s must land in a queue, never nowhere", holdingVertical)
                .isNotNull();

        // F-1-a is NOT resolved here. Whether the offending key names belong on the row is an
        // open semantic question and no contract document defines this payload, so the row is
        // exactly what the pre-existing lenient branch already built — no field was added
        // because it would have been convenient. Asserted so a later addition is deliberate.
        assertThat(item.keySet())
                .as("validation_gap payload must be untouched by F-1")
                .containsExactlyInAnyOrder("_id", "type", "vertical_id", "status", "created_at");
    }

    /**
     * The other half of "nothing accepted today becomes rejected": leniency must not leak the
     * other way either. A schema'd vertical resolves a schema, takes the strict arm, and must
     * therefore raise NO validation_gap — otherwise the fix would have queued a governance gap
     * against all 293 real verticals.
     */
    @Test
    void a_valid_mint_on_a_schemad_vertical_raises_no_validation_gap() {
        ResponseEntity<JsonNode> res = post("/api/v1/products",
                draft("TZP-F1-F", RICE, "provisional", Map.of("pack_size", 5, "pack_unit", "kg")),
                CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(db.getCollection("work_queue").find(eq("_id", "validation_gap:" + RICE)).first())
                .as("a vertical WITH a schema is governed, not gapped")
                .isNull();
    }

    // ------------------------------------------------------------------ 3: strict path intact

    @Test
    void unregistered_key_on_a_schemad_vertical_is_still_rejected() {
        ResponseEntity<JsonNode> res = post("/api/v1/products",
                draft("TZP-F1-C", RICE, "provisional",
                        Map.of("pack_size", 5, "pack_unit", "kg", BOGUS_KEY, "xyz")),
                CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().at("/error/code").asText())
                .as("the strict branch is untouched: leniency must not leak to real verticals")
                .isEqualTo("ATTRIBUTE_VIOLATION");
        assertThat(db.getCollection("products").find(eq("_id", "TZP-F1-C")).first()).isNull();
    }

    // ------------------------------------------------- 4 + 5: the loop OUTSIDE the if/else

    /**
     * The claim-tier evidence gate lives in the second loop, outside the branch. It was never
     * part of the hole and must still fire on a holding vertical. If this test ever passes a
     * product through, the fix has widened leniency instead of narrowing silence.
     */
    @Test
    void claim_tier_attribute_without_evidence_is_still_rejected_on_a_holding_vertical() {
        ResponseEntity<JsonNode> res = post("/api/v1/products",
                draft("TZP-F1-D", MintService.UNCLASSIFIED, "review",
                        Map.of("organic_certified", true)),
                CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("ATTRIBUTE_VIOLATION");
        assertThat(res.getBody().at("/error/message").asText()).contains("organic_certified");
        assertThat(db.getCollection("products").find(eq("_id", "TZP-F1-D")).first()).isNull();
    }

    /** Same argument for the declared-type check: pack_size is `number`, "five" is a String. */
    @Test
    void type_invalid_value_is_still_rejected_on_a_holding_vertical() {
        ResponseEntity<JsonNode> res = post("/api/v1/products",
                draft("TZP-F1-E", MintService.UNCLASSIFIED, "review",
                        Map.of("pack_size", "five", "pack_unit", "kg")),
                CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("ATTRIBUTE_VIOLATION");
        assertThat(res.getBody().at("/error/message").asText()).contains("pack_size");
        assertThat(db.getCollection("products").find(eq("_id", "TZP-F1-E")).first()).isNull();
    }

    // ------------------------------------------------------------------ F-1-b census

    /**
     * F-1-b — READ ONLY. Which verticals in the LOADED taxonomy (not the seed file) have no
     * resolvable schema? Two populations, because {@code schema == null} in validate() has two
     * causes and only one of them is "attribute_schema_id is null":
     *   (a) attribute_schema_id null or absent;
     *   (b) attribute_schema_id set, but no active version of it exists in attribute_schemas.
     * Nothing is altered and no schema is assigned. The list is reported, not fixed.
     */
    @Test
    void census_of_schemaless_verticals_in_the_loaded_taxonomy() {
        List<String> nullSchemaId = new ArrayList<>();
        db.getCollection("taxonomy_nodes")
                .find(and(eq("node_type", "vertical"),
                        or(eq("attribute_schema_id", null), exists("attribute_schema_id", false))))
                .forEach(d -> nullSchemaId.add(d.getString("_id") + " | " + d.getString("name")
                        + " | status=" + d.getString("status")));
        nullSchemaId.sort(String::compareTo);

        List<String> unresolvableSchemaId = new ArrayList<>();
        TreeSet<String> missingSchemaIds = new TreeSet<>();
        db.getCollection("taxonomy_nodes")
                .find(and(eq("node_type", "vertical"), exists("attribute_schema_id"),
                        com.mongodb.client.model.Filters.ne("attribute_schema_id", null)))
                .forEach(d -> {
                    String sid = d.getString("attribute_schema_id");
                    Document s = com.tazzzo.catalog.tx.AttributeAuthoringService.latestActiveIn(
                            db, null, "attribute_schemas", eq("schema_id", sid));
                    if (s == null) {
                        unresolvableSchemaId.add(d.getString("_id") + " | " + d.getString("name")
                                + " | attribute_schema_id=" + sid);
                        missingSchemaIds.add(sid);
                    }
                });
        unresolvableSchemaId.sort(String::compareTo);

        long verticals = db.getCollection("taxonomy_nodes").countDocuments(eq("node_type", "vertical"));

        System.out.println();
        System.out.println("================================================================");
        System.out.println("F-1-b CENSUS — schema-less verticals in the LOADED taxonomy");
        System.out.println("READ ONLY. Nothing below was changed. No schema was assigned.");
        System.out.println("================================================================");
        System.out.printf("  node_type=vertical, total                          : %d%n", verticals);
        System.out.printf("  (a) attribute_schema_id null/absent                : %d%n",
                nullSchemaId.size());
        for (String s : nullSchemaId) System.out.println("        " + s);
        if (nullSchemaId.isEmpty()) System.out.println("        (none)");
        System.out.printf("  (b) attribute_schema_id set but not resolvable     : %d%n",
                unresolvableSchemaId.size());
        for (String s : unresolvableSchemaId) System.out.println("        " + s);
        if (unresolvableSchemaId.isEmpty()) System.out.println("        (none)");
        System.out.printf("  distinct unresolvable schema_ids                   : %s%n",
                missingSchemaIds);
        System.out.println("  Both populations reach `schema == null` in validate(), so both are");
        System.out.println("  governed by the branch this segment changes.");

        assertThat(nullSchemaId)
                .as("the two known holding nodes must appear; any THIRD entry is an F-1-b "
                        + "finding to escalate, not to fix here")
                .hasSize(2)
                .anyMatch(s -> s.startsWith(MintService.UNCLASSIFIED))
                .anyMatch(s -> s.startsWith(MintService.SCOPE_BLOCKED));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> draft(String id, String verticalId, String classificationStatus,
                                      Map<String, Object> attributes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", "f1|" + id);
        m.put("brandCode", "BR-F1");
        m.put("title", "F-1 probe " + id);
        m.put("verticalId", verticalId);
        m.put("releaseId", RELEASE);
        m.put("classificationStatus", classificationStatus);
        m.put("attributes", attributes);
        m.put("evidenceRefs", List.of());
        return m;
    }
}
