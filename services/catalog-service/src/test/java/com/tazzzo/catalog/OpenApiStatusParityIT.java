package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statuses the generated contract documents are the statuses the service really answers: each non-200 success (201,
 * 202, and the 201-first / 200-update upserts) and a spread of error statuses are produced over real HTTP, and every one
 * must be listed on the operation it came from.
 */
class OpenApiStatusParityIT extends AbstractApiIT {

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;

    JsonNode spec;

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        spec = get("/v3/api-docs", READ_TOKEN, JsonNode.class).getBody();
        // the product the other tests build on: a create is documented 201 (not 200)
        expect(call(HttpMethod.POST, "/api/v1/products", "/api/v1/products", product("TZP-OAS-1", "oas|1"), CMS_TOKEN), 201);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String template, String path, Object body, String token) {
        ResponseEntity<JsonNode> res = rest.exchange(url(path), method, new HttpEntity<>(body, headers(token)), JsonNode.class);
        JsonNode op = spec.at("/paths").get(template).get(method.name().toLowerCase());
        assertThat(op).as(method + " " + template + " is documented").isNotNull();
        assertThat(op.get("responses").has(String.valueOf(res.getStatusCode().value())))
                .as(method + " " + template + " answered " + res.getStatusCode().value() + " (" + res.getBody()
                        + "); documented: " + op.get("responses").fieldNames().next() + "...")
                .isTrue();
        return res;
    }

    private void expect(ResponseEntity<JsonNode> res, int status) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(status);
    }

    private static Map<String, Object> product(String id, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", key);
        m.put("brandCode", "BR-OAS");
        m.put("title", "OAS " + id);
        m.put("verticalId", "TZV-000001");
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        return m;
    }

    @Test
    void creates_answer_201_and_upserts_answer_201_then_200() {

        String price = "/api/v1/admin/prices/TZP-OAS-1";
        expect(call(HttpMethod.PUT, "/api/v1/admin/prices/{skuId}", price, Map.of("sellingPricePaise", 900, "mrpPaise", 1000), CMS_TOKEN), 201);
        expect(call(HttpMethod.PUT, "/api/v1/admin/prices/{skuId}", price,
                Map.of("sellingPricePaise", 800, "mrpPaise", 1000, "expectedVersion", 1), CMS_TOKEN), 200);

        String stock = "/api/v1/admin/inventory/TZP-OAS-1/FL-1";
        Map<String, Object> s = new LinkedHashMap<>(Map.of("onHand", 10, "lowStockThreshold", 2, "maxPurchasable", 5));
        expect(call(HttpMethod.PUT, "/api/v1/admin/inventory/{skuId}/{locationId}", stock, s, CMS_TOKEN), 201);
        s.put("expectedVersion", 1);
        expect(call(HttpMethod.PUT, "/api/v1/admin/inventory/{skuId}/{locationId}", stock, s, CMS_TOKEN), 200);

        Map<String, Object> area = new LinkedHashMap<>();
        area.put("serviceAreaId", "SA-OAS");
        area.put("routes", List.of(Map.of("fulfillmentLocationId", "FL-1", "priority", 0, "active", true)));
        expect(call(HttpMethod.PUT, "/api/v1/admin/service-areas/{pincode}", "/api/v1/admin/service-areas/560077", area, CMS_TOKEN), 201);
        area.put("expectedVersion", 1);
        expect(call(HttpMethod.PUT, "/api/v1/admin/service-areas/{pincode}", "/api/v1/admin/service-areas/560077", area, CMS_TOKEN), 200);

        expect(call(HttpMethod.POST, "/api/v1/admin/imports/jobs", "/api/v1/admin/imports/jobs",
                Map.of("kind", "products", "note", "oas"), CMS_TOKEN), 201);
    }

    @Test
    void taxonomy_and_evidence_statuses() {
        expect(call(HttpMethod.POST, "/api/v1/taxonomy/releases", "/api/v1/taxonomy/releases",
                Map.of("releaseId", "0.9.9", "basedOn", "0.9.0"), CMS_TOKEN), 201);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeType", "super_category");
        node.put("name", "OAS Super");
        node.put("parentId", null);
        expect(call(HttpMethod.POST, "/api/v1/taxonomy/nodes", "/api/v1/taxonomy/nodes", node, CMS_TOKEN), 201);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", "EV-OAS-1");
        ev.put("evidenceType", "lab_report");
        ev.put("source", "supplier-acme");
        ev.put("sourceVersion", "batch-2026-08");
        ev.put("payloadRef", Map.of("store", "media", "objectId", "obj_oas", "sha256", "abc"));
        ev.put("excerpt", "sugar 2.1g");
        ev.put("url", "https://example.test/doc");
        expect(call(HttpMethod.POST, "/api/v1/evidence", "/api/v1/evidence", ev, CMS_TOKEN), 201);
        expect(call(HttpMethod.POST, "/api/v1/evidence", "/api/v1/evidence", ev, CMS_TOKEN), 200);   // identical replay
        expect(call(HttpMethod.POST, "/api/v1/evidence/{id}/retract", "/api/v1/evidence/EV-OAS-1/retract",
                Map.of("to", "retracted", "reason", "withdrawn"), CMS_TOKEN), 202);
    }

    @Test
    void error_statuses_are_documented_on_the_operation_that_produced_them() {
        String p = "/api/v1/products";
        expect(call(HttpMethod.POST, p, p, product("TZP-OAS-E", "oas|e"), null), 401);
        expect(call(HttpMethod.POST, p, p, product("TZP-OAS-E", "oas|e"), READ_TOKEN), 403);
        Map<String, Object> bad = product("TZP-OAS-E", "oas|e");
        bad.put("attributes", Map.of("not_a_real_key", "x"));
        expect(call(HttpMethod.POST, p, p, bad, CMS_TOKEN), 422);
        expect(call(HttpMethod.GET, "/api/v1/products/{id}", "/api/v1/products/TZP-NOPE", null, READ_TOKEN), 404);
        expect(call(HttpMethod.GET, "/api/v1/products", "/api/v1/products?canonicalKey=none", null, READ_TOKEN), 404);
        expect(call(HttpMethod.GET, "/api/v1/products", "/api/v1/products?bogus=1", null, READ_TOKEN), 400);
        expect(call(HttpMethod.GET, "/api/v1/products", "/api/v1/products?limit=2", null, READ_TOKEN), 200);

        // 409: a replayed create collides on the identity registry
        expect(call(HttpMethod.POST, p, p, product("TZP-OAS-1", "oas|1"), CMS_TOKEN), 409);

        // 413: a body over the 64 KiB default, refused before authentication
        Map<String, Object> huge = product("TZP-OAS-BIG", "oas|big");
        huge.put("title", "x".repeat(70_000));
        expect(call(HttpMethod.POST, p, p, huge, CMS_TOKEN), 413);
        expect(call(HttpMethod.POST, p, p, huge, null), 413);
    }

    @Test
    void app_surface_statuses_are_documented_on_the_operation_that_produced_them() {
        // 401: no bearer on a customer route
        expect(call(HttpMethod.GET, "/v1/customer/profile", "/v1/customer/profile", null, null), 401);
        expect(call(HttpMethod.POST, "/v1/auth/logout", "/v1/auth/logout", null, null), 401);
        // 400 / 413 on the public OTP request (flat envelope)
        expect(call(HttpMethod.POST, "/v1/auth/otp/request", "/v1/auth/otp/request", Map.of("phone", "not-a-phone"), null), 400);
        expect(call(HttpMethod.POST, "/v1/auth/otp/request", "/v1/auth/otp/request", Map.of("phone", "9".repeat(70_000)), null), 413);
        // a public route answers in the family envelope with a code
        ResponseEntity<JsonNode> missing = call(HttpMethod.GET, "/v1/products/{id}", "/v1/products/TZP-NOPE", null, null);
        assertThat(missing.getBody().has("code")).isTrue();
    }
}
