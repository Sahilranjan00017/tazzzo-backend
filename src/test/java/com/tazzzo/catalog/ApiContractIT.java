package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step-3 API suite. T-RULE-1 applies to HTTP: an expected rejection must assert the stable
 * ERROR CODE, never merely a 4xx.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiContractIT extends AbstractApiIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;

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
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().at("/error/code").asText())
                .as("stable error code (T-RULE-1)").isEqualTo(code);
        assertThat(res.getBody().at("/error/request_id").asText()).startsWith("req_");
    }

    private Map<String, Object> riceProduct(String id, String key) {
        return product(id, key, Map.of("pack_size", 5, "pack_unit", "kg"));
    }

    private Map<String, Object> product(String id, String key, Map<String, Object> attributes) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", key);
        m.put("brandCode", "BR-API");
        m.put("title", "API " + id);
        m.put("verticalId", BASMATI);
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", attributes);
        m.put("evidenceRefs", List.of());
        return m;
    }

    @Test @Order(1)
    void authentication_and_authorization_boundary() {
        assertErrorCode(post("/api/v1/products", riceProduct("TZP-API-X", "api|x"), null, JsonNode.class),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertErrorCode(post("/api/v1/products", riceProduct("TZP-API-X", "api|x"), "bogus-token", JsonNode.class),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        assertErrorCode(post("/api/v1/products", riceProduct("TZP-API-X", "api|x"), READ_TOKEN, JsonNode.class),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        // reader CAN read
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, READ_TOKEN, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.getCollection("products").countDocuments()).isZero();
    }

    @Test @Order(2)
    void create_read_and_taxonomy_path_projection() {
        ResponseEntity<JsonNode> created = post("/api/v1/products",
                riceProduct("TZP-API-1", "api|1"), CMS_TOKEN, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
        assertThat(created.getBody().get("taxonomyPath").asText())
                .isEqualTo("Staples > Rice & Grains > Basmati Rice > Basmati Rice");
        ResponseEntity<JsonNode> got = get("/api/v1/products/TZP-API-1", READ_TOKEN, JsonNode.class);
        assertThat(got.getBody().get("version").asInt()).isEqualTo(1);
        assertThat(got.getBody().at("/attributes/pack_size").asInt()).isEqualTo(5);
    }

    @Test @Order(3)
    void domain_errors_map_to_stable_codes() {
        // duplicate identity -> IDENTITY_COLLISION
        assertErrorCode(post("/api/v1/products", riceProduct("TZP-API-2", "api|1"), CMS_TOKEN, JsonNode.class),
                HttpStatus.CONFLICT, "IDENTITY_COLLISION");
        // unknown attribute key -> ATTRIBUTE_VIOLATION
        Map<String, Object> bad = product("TZP-API-3", "api|3",
                Map.of("pack_size", 1, "pack_unit", "kg", "not_a_real_key", "x"));
        assertErrorCode(post("/api/v1/products", bad, CMS_TOKEN, JsonNode.class),
                HttpStatus.UNPROCESSABLE_ENTITY, "ATTRIBUTE_VIOLATION");
        // taxonomy change with no open release -> NO_OPEN_RELEASE (409)
        assertErrorCode(post("/api/v1/taxonomy/nodes/" + BASMATI + "/rename",
                        Map.of("name", "X", "expectedVersion", 1), CMS_TOKEN, JsonNode.class),
                HttpStatus.CONFLICT, "NO_OPEN_RELEASE");
        // unknown node -> code asserted, not just the status (T-RULE-1)
        assertErrorCode(get("/api/v1/taxonomy/nodes/TZV-999999", READ_TOKEN, JsonNode.class),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test @Order(4)
    void optimistic_concurrency_via_if_match() {
        HttpHeaders h = headers(CMS_TOKEN);
        h.set("If-Match", "1");
        ResponseEntity<JsonNode> ok = rest.exchange(url("/api/v1/products/TZP-API-1"),
                HttpMethod.PATCH, new HttpEntity<>(Map.of("title", "Renamed"), h), JsonNode.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("version").asInt()).isEqualTo(2);
        HttpHeaders stale = headers(CMS_TOKEN);
        stale.set("If-Match", "1");
        assertErrorCode(rest.exchange(url("/api/v1/products/TZP-API-1"), HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("title", "Ghost"), stale), JsonNode.class),
                HttpStatus.CONFLICT, "STALE_VERSION");
        assertThat(get("/api/v1/products/TZP-API-1", READ_TOKEN, JsonNode.class)
                .getBody().get("title").asText()).isEqualTo("Renamed");
    }

    @Test @Order(5)
    void malformed_requests_are_rejected_with_codes() {
        HttpHeaders h = headers(CMS_TOKEN);
        h.set("If-Match", "2");
        assertErrorCode(rest.exchange(url("/api/v1/products/TZP-API-1"), HttpMethod.PATCH,
                        new HttpEntity<>(Map.of(), h), JsonNode.class),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        // missing expectedVersion on a taxonomy op
        assertErrorCode(post("/api/v1/taxonomy/nodes/" + BASMATI + "/rename",
                        Map.of("name", "NoVersion"), CMS_TOKEN, JsonNode.class),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test @Order(5)
    void mvc_errors_map_to_client_codes_not_500() {
        // missing If-Match header
        assertErrorCode(rest.exchange(url("/api/v1/products/TZP-API-1"), HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("title", "x"), headers(CMS_TOKEN)), JsonNode.class),
                HttpStatus.BAD_REQUEST, "MISSING_HEADER");
        // quoted ETag (RFC form) is a client error, never a 500
        HttpHeaders quoted = headers(CMS_TOKEN);
        quoted.set("If-Match", "\"2\"");
        assertErrorCode(rest.exchange(url("/api/v1/products/TZP-API-1"), HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("title", "x"), quoted), JsonNode.class),
                HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        // unknown route under /api
        assertErrorCode(get("/api/v1/nope", READ_TOKEN, JsonNode.class),
                HttpStatus.NOT_FOUND, "NO_SUCH_ENDPOINT");
        // wrong method
        assertErrorCode(rest.exchange(url("/api/v1/products/TZP-API-1"), HttpMethod.DELETE,
                        new HttpEntity<>(headers(CMS_TOKEN)), JsonNode.class),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
    }

    @Test @Order(6)
    void release_conflict_codes() {
        assertThat(post("/api/v1/taxonomy/releases", Map.of("releaseId", "3.0.0", "basedOn", "0.9.0"),
                CMS_TOKEN, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertErrorCode(post("/api/v1/taxonomy/releases", Map.of("releaseId", "3.1.0", "basedOn", "0.9.0"),
                        CMS_TOKEN, JsonNode.class), HttpStatus.CONFLICT, "RELEASE_ALREADY_OPEN");
        // rename now works through HTTP under the open release
        ResponseEntity<JsonNode> renamed = post("/api/v1/taxonomy/nodes/" + BASMATI + "/rename",
                Map.of("name", "Basmati Rice (API)", "expectedVersion", 1), CMS_TOKEN, JsonNode.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("version").asInt()).isEqualTo(2);
        assertThat(post("/api/v1/taxonomy/releases/3.0.0/publish", Map.of(), CMS_TOKEN, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(db.getCollection("catalogue_releases")
                .find(new org.bson.Document("_id", "3.0.0")).first().getString("status"))
                .isEqualTo("active");
    }

    @Test @Order(7)
    void domain_state_conflicts_are_409_not_404() {
        post("/api/v1/products", riceProduct("TZP-API-S1", "api|s1"), CMS_TOKEN, JsonNode.class);
        post("/api/v1/products", riceProduct("TZP-API-S2", "api|s2"), CMS_TOKEN, JsonNode.class);
        // both are lifecycle=draft -> merge must refuse with a STATE conflict, never NOT_FOUND
        assertErrorCode(post("/api/v1/products/TZP-API-S1/merge/TZP-API-S2", Map.of(),
                CMS_TOKEN, JsonNode.class), HttpStatus.CONFLICT, "STATE_CONFLICT");
    }

    @Test @Order(8)
    void release_publish_retry_is_a_conflict_not_a_404() {
        assertErrorCode(post("/api/v1/taxonomy/releases/3.0.0/publish", Map.of(), CMS_TOKEN,
                JsonNode.class), HttpStatus.CONFLICT, "RELEASE_NOT_OPEN");
    }

    @Test @Order(8)
    void f2_missing_product_is_404_not_409() {
        // classify / gtin / publish against a non-existent product
        assertErrorCode(post("/api/v1/products/TZP-NOPE/classify",
                Map.of("verticalId", BASMATI, "releaseId", "0.9.0", "status", "confirmed"),
                CMS_TOKEN, JsonNode.class), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertErrorCode(post("/api/v1/products/TZP-NOPE/gtins",
                Map.of("gtin", "8909999999999", "market", "IN"),
                CMS_TOKEN, JsonNode.class), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertErrorCode(post("/api/v1/products/TZP-NOPE/merge/TZP-ALSO-NOPE", Map.of(),
                CMS_TOKEN, JsonNode.class), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertErrorCode(get("/api/v1/products/TZP-NOPE", READ_TOKEN, JsonNode.class),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test @Order(9)
    void responses_expose_dtos_not_mongo_documents() {
        JsonNode release = get("/api/v1/taxonomy/releases/3.0.0", READ_TOKEN, JsonNode.class).getBody();
        assertThat(release.has("gate")).as("internal fields never leak").isFalse();
        assertThat(release.has("change_seq")).isFalse();
        assertThat(release.get("status").asText()).isEqualTo("active");
        JsonNode attr = get("/api/v1/attributes/pack_size", READ_TOKEN, JsonNode.class).getBody();
        assertThat(attr.has("_id")).isFalse();
        assertThat(attr.get("key").asText()).isEqualTo("pack_size");
        JsonNode schema = get("/api/v1/attribute-schemas/rice", READ_TOKEN, JsonNode.class).getBody();
        assertThat(schema.get("schemaId").asText()).isEqualTo("rice");
        assertThat(schema.get("fields").isArray()).isTrue();
    }

    @Test @Order(10)
    void merge_is_accepted_asynchronously() {
        post("/api/v1/products", riceProduct("TZP-API-M1", "api|m1"), CMS_TOKEN, JsonNode.class);
        post("/api/v1/products", riceProduct("TZP-API-M2", "api|m2"), CMS_TOKEN, JsonNode.class);
        db.getCollection("products").updateMany(
                new org.bson.Document("_id", new org.bson.Document("$in", List.of("TZP-API-M1", "TZP-API-M2"))),
                new org.bson.Document("$set", new org.bson.Document("lifecycle", "active")));
        ResponseEntity<JsonNode> res = post("/api/v1/products/TZP-API-M1/merge/TZP-API-M2",
                Map.of(), CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(res.getBody().get("status").asText()).isEqualTo("merging");
        assertThat(db.getCollection("work_queue")
                .find(new org.bson.Document("_id", "merge:TZP-API-M1:TZP-API-M2")).first())
                .as("outbox obligation committed by the domain, not the controller").isNotNull();
    }
}
