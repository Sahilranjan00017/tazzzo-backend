package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaintService;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/** Evidence API — every rule in the approved contract, asserted by CODE (T-RULE-1). */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvidenceApiIT extends AbstractApiIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired TaintService taintService;

    static final String BASMATI = "TZV-000001";

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline("0.9.0");
    }

    private void assertErrorCode(ResponseEntity<JsonNode> res, HttpStatus status, String code) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo(code);
    }

    private Map<String, Object> evidenceBody(String id, String excerpt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("evidenceType", "lab_report");
        m.put("source", "supplier-acme");
        m.put("sourceVersion", "batch-2026-08");
        m.put("payloadRef", Map.of("store", "media", "objectId", "obj_9f2", "sha256", "abc"));
        m.put("excerpt", excerpt);
        m.put("url", "https://example.test/doc");
        return m;
    }

    @Test @Order(1)
    void create_returns_201_and_never_exposes_internal_fields() {
        ResponseEntity<JsonNode> res = post("/api/v1/evidence",
                evidenceBody("EV-API-1", "sugar 2.1g"), CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode b = res.getBody();
        assertThat(b.get("validity").asText()).isEqualTo("active");
        assertThat(b.get("payloadState").asText()).isEqualTo("readable");
        assertThat(b.at("/payloadRef/objectId").asText()).isEqualTo("obj_9f2");
        assertThat(b.has("fence")).as("internal fence must never be exposed").isFalse();
        assertThat(b.has("_id")).isFalse();
        // audit event written through WritePath
        assertThat(db.getCollection("product_events")
                .countDocuments(eq("type", "EVIDENCE_CREATED"))).isEqualTo(1);
    }

    @Test @Order(2)
    void identical_replay_is_idempotent_200_and_writes_nothing() {
        long eventsBefore = db.getCollection("product_events").countDocuments();
        ResponseEntity<JsonNode> res = post("/api/v1/evidence",
                evidenceBody("EV-API-1", "sugar 2.1g"), CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).as("identical replay -> 200, not an error")
                .isEqualTo(HttpStatus.OK);
        assertThat(db.getCollection("product_events").countDocuments())
                .as("replay must write nothing").isEqualTo(eventsBefore);
    }

    @Test @Order(3)
    void differing_replay_is_refused_409_evidence_immutable() {
        assertErrorCode(post("/api/v1/evidence", evidenceBody("EV-API-1", "DIFFERENT TEXT"),
                CMS_TOKEN, JsonNode.class), HttpStatus.CONFLICT, "EVIDENCE_IMMUTABLE");
    }

    @Test @Order(4)
    void raw_payload_is_refused_and_bad_input_is_coded() {
        Map<String, Object> withBytes = evidenceBody("EV-API-2", "x");
        withBytes.put("payload", "JVBERi0xLjQK...");
        assertErrorCode(post("/api/v1/evidence", withBytes, CMS_TOKEN, JsonNode.class),
                HttpStatus.UNPROCESSABLE_ENTITY, "PAYLOAD_NOT_ACCEPTED");
        Map<String, Object> badId = evidenceBody("NOT-EV-3", "x");
        assertErrorCode(post("/api/v1/evidence", badId, CMS_TOKEN, JsonNode.class),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        // input validation is consistently 400 MALFORMED_REQUEST (review point 7)
        Map<String, Object> badType = evidenceBody("EV-API-4", "x");
        badType.put("evidenceType", "not_a_type");
        assertErrorCode(post("/api/v1/evidence", badType, CMS_TOKEN, JsonNode.class),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        // omitted evidenceType must NOT 500 (review MAJOR: NPE via Set.of().contains(null))
        Map<String, Object> noType = evidenceBody("EV-API-5", "x");
        noType.remove("evidenceType");
        assertErrorCode(post("/api/v1/evidence", noType, CMS_TOKEN, JsonNode.class),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test @Order(4)
    void observed_at_is_validated_returned_and_part_of_identity() {
        // malformed timestamp must NOT 500 (review MAJOR: DateTimeParseException)
        Map<String, Object> bad = evidenceBody("EV-OBS-1", "x");
        bad.put("observedAt", "2026-08-27");           // date only, not an instant
        assertErrorCode(post("/api/v1/evidence", bad, CMS_TOKEN, JsonNode.class),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        // valid instant is accepted and RETURNED (no longer write-only)
        Map<String, Object> ok = evidenceBody("EV-OBS-2", "x");
        ok.put("observedAt", "2026-08-26T10:00:00Z");
        ResponseEntity<JsonNode> created = post("/api/v1/evidence", ok, CMS_TOKEN, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("observedAt").asText()).isEqualTo("2026-08-26T10:00:00Z");
        // identical replay incl. observedAt -> 200
        assertThat(post("/api/v1/evidence", ok, CMS_TOKEN, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // DIFFERING caller-supplied observedAt -> 409 (review MAJOR: was silently accepted)
        Map<String, Object> shifted = evidenceBody("EV-OBS-2", "x");
        shifted.put("observedAt", "2026-08-26T11:00:00Z");
        assertErrorCode(post("/api/v1/evidence", shifted, CMS_TOKEN, JsonNode.class),
                HttpStatus.CONFLICT, "EVIDENCE_IMMUTABLE");
    }

    @Test @Order(5)
    void get_returns_dto_and_404_for_unknown() {
        JsonNode b = get("/api/v1/evidence/EV-API-1", READ_TOKEN, JsonNode.class).getBody();
        assertThat(b.get("source").asText()).isEqualTo("supplier-acme");
        assertThat(b.has("fence")).isFalse();
        assertErrorCode(get("/api/v1/evidence/EV-NOPE", READ_TOKEN, JsonNode.class),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test @Order(6)
    void authorization_boundary_holds() {
        assertErrorCode(post("/api/v1/evidence", evidenceBody("EV-API-9", "x"), READ_TOKEN,
                JsonNode.class), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertErrorCode(post("/api/v1/evidence", evidenceBody("EV-API-9", "x"), null,
                JsonNode.class), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test @Order(7)
    void end_to_end_create_publish_retract_taint() {
        // a product to carry the claim
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "TZP-EV-1");
        p.put("productType", "single");
        p.put("identityType", "internal");
        p.put("internalKey", "ev|1");
        p.put("brandCode", "BR-EV");
        p.put("title", "Evidence flow");
        p.put("verticalId", BASMATI);
        p.put("releaseId", "0.9.0");
        p.put("classificationStatus", "provisional");
        p.put("attributes", Map.of("pack_size", 1, "pack_unit", "kg"));
        p.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products", p, CMS_TOKEN, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // evidence created THROUGH THE API (no mongosh insert — the gap this API closes)
        post("/api/v1/evidence", evidenceBody("EV-FLOW", "lab certified"), CMS_TOKEN, JsonNode.class);

        // publish a claim citing it
        ResponseEntity<JsonNode> pub = post("/api/v1/products/TZP-EV-1/publish",
                Map.of("attributeKey", "lab_ok", "evidenceRefs", List.of("EV-FLOW")),
                CMS_TOKEN, JsonNode.class);
        assertThat(pub.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pub.getBody().at("/attributes/lab_ok_published").asBoolean()).isTrue();

        // retract via the API -> 202 + cascade queued in the SAME transaction
        ResponseEntity<JsonNode> ret = post("/api/v1/evidence/EV-FLOW/retract",
                Map.of("to", "retracted", "reason", "supplier withdrew"), CMS_TOKEN, JsonNode.class);
        assertThat(ret.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ret.getBody().get("cascade").asText()).isEqualTo("queued");
        assertThat(db.getCollection("work_queue").find(eq("_id", "taint:EV-FLOW")).first())
                .as("cascade item enqueued with the validity flip").isNotNull();

        // the gate now refuses a new claim on that evidence
        assertErrorCode(post("/api/v1/products/TZP-EV-1/publish",
                Map.of("attributeKey", "lab_ok2", "evidenceRefs", List.of("EV-FLOW")),
                CMS_TOKEN, JsonNode.class), HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_GATE");

        // run the worker -> per-product revalidation obligation exists
        taintService.runTaintWorker(50, -1);
        assertThat(db.getCollection("work_queue")
                .find(eq("_id", "attr_reval:EV-FLOW:TZP-EV-1")).first())
                .as("revalidation queued for the citing product").isNotNull();
    }

    @Test @Order(8)
    void retract_is_idempotent_reactivation_forbidden_unknown_is_404() {
        assertThat(post("/api/v1/evidence/EV-FLOW/retract", Map.of("to", "retracted"),
                CMS_TOKEN, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertErrorCode(post("/api/v1/evidence/EV-FLOW/retract", Map.of("to", "active"),
                CMS_TOKEN, JsonNode.class), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_VALIDITY_TRANSITION");
        assertErrorCode(post("/api/v1/evidence/EV-GHOST/retract", Map.of("to", "retracted"),
                CMS_TOKEN, JsonNode.class), HttpStatus.NOT_FOUND, "NOT_FOUND");
    }
}
