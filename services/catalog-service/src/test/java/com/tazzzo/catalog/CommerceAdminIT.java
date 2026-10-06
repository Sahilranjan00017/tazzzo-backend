package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.TestActors;
import org.bson.Document;
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

/** PR-H/I: the price and stock admin APIs — auth, validation, CAS, reservation guard, lifecycle, attributed audit. */
class CommerceAdminIT extends AbstractApiIT {

    static final String SKU = "TZP-ADM-1";
    static final String PRICE = "/api/v1/admin/prices/" + SKU;
    static final String STOCK = "/api/v1/admin/inventory/" + SKU + "/FL-1";
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", SKU);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", "adm|1");
        m.put("brandCode", "BR-ADM");
        m.put("title", "Admin " + SKU);
        m.put("verticalId", "TZV-000001");
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products", m, "cms-test-token", JsonNode.class).getStatusCode().value()).isEqualTo(201);
    }

    static final String W = "cms-test-token";
    static final String R = "read-test-token";

    private ResponseEntity<JsonNode> put(String path, Object body, String token) {
        return rest.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private static Map<String, Object> price(long selling, long mrp, Long expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sellingPricePaise", selling);
        m.put("mrpPaise", mrp);
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    private static Map<String, Object> stock(long onHand, Long expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("onHand", onHand);
        m.put("lowStockThreshold", 5);
        m.put("maxPurchasable", 10);
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    private void assertCode(ResponseEntity<JsonNode> res, int status, String code) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(status);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo(code);
    }

    // ------------------------------------------------------------------ prices

    @Test
    void price_create_read_update_and_stale() {
        assertCode(get(PRICE, R, JsonNode.class), 404, "NOT_FOUND");
        ResponseEntity<JsonNode> created = put(PRICE, price(9900, 12000, null), W);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("sellingPricePaise").asLong()).isEqualTo(9900);
        assertThat(created.getBody().get("version").asLong()).isEqualTo(1);
        assertThat(created.getBody().get("currency").asText()).isEqualTo("INR");
        assertThat(created.getBody().get("status").asText()).isEqualTo("ACTIVE");
        assertCode(put(PRICE, price(9900, 12000, null), W), 409, "STALE_VERSION");        // create is not an update
        ResponseEntity<JsonNode> updated = put(PRICE, price(8800, 12000, 1L), W);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().get("version").asLong()).isEqualTo(2);
        assertCode(put(PRICE, price(7700, 12000, 1L), W), 409, "STALE_VERSION");
        assertThat(get(PRICE, R, JsonNode.class).getBody().get("sellingPricePaise").asLong()).as("reader may read").isEqualTo(8800);
    }

    @Test
    void price_validation_auth_and_unknown_product() {
        String sku2 = "/api/v1/admin/prices/TZP-ADM-NONE";
        assertCode(put(sku2, price(100, 200, null), W), 404, "NOT_FOUND");
        assertCode(get(sku2, R, JsonNode.class), 404, "NOT_FOUND");
        assertThat(db.getCollection("price_current").countDocuments(Filters.eq("sku_id", "TZP-ADM-NONE"))).as("nothing written for an unknown SKU").isZero();
        assertThat(db.getCollection("price_events").countDocuments(Filters.eq("sku_id", "TZP-ADM-NONE"))).isZero();
        String other = PRICE + "?x=1";
        assertThat(put(PRICE, price(1, 2, null), null).getStatusCode().value()).isEqualTo(401);
        assertThat(put(PRICE, price(1, 2, null), R).getStatusCode().value()).as("read-only cannot write").isEqualTo(403);
        assertCode(put(PRICE, price(30001, 30000, 2L), W), 422, "INVALID_PRICE");          // mrp below selling
        assertCode(put(PRICE, price(-1, 30000, 2L), W), 422, "INVALID_PRICE");
        assertCode(put(PRICE, price(2_000_000_000L, 2_000_000_000L, 2L), W), 422, "INVALID_PRICE");   // fat-finger ceiling
        assertCode(put(PRICE, Map.of("sellingPricePaise", 100), W), 422, "INVALID_PRICE");              // mrp missing
        Map<String, Object> badCur = price(100, 200, 2L);
        badCur.put("currency", "XYZ");
        assertCode(put(PRICE, badCur, W), 422, "INVALID_PRICE");
        Map<String, Object> window = price(100, 200, 2L);
        window.put("effectiveFrom", "2030-01-01T00:00:00Z");
        window.put("actor", Map.of("id", "evil"));
        assertThat(put(PRICE, window, W).getStatusCode().value()).as("unknown fields never become a window or an actor").isIn(200, 400);
        assertThat(get(PRICE, R, JsonNode.class).getBody().get("version").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void price_writes_are_attributed_to_the_authenticated_actor() {
        put(PRICE, price(5500, 6000, db.getCollection("price_current").find(Filters.eq("sku_id", SKU)).first() == null ? null
                : ((Number) db.getCollection("price_current").find(Filters.eq("sku_id", SKU)).first().get("version")).longValue()), W);
        List<Document> rows = db.getCollection("price_events").find(Filters.eq("sku_id", SKU)).into(new java.util.ArrayList<>());
        assertThat(rows).isNotEmpty();
        for (Document row : rows) {
            if (row.get("actor") == null) continue;   // rows seeded by the first test also carry one; none may carry the body's
            assertThat(row.toJson()).doesNotContain("evil");
        }
        assertThat(rows).allSatisfy(row -> assertThat(ActorDocuments.fromEvent(row)).as("every ledger row is attributed").isPresent());
        assertThat(rows.get(0).toJson()).doesNotContain("cms-test-token");
    }

    // --------------------------------------------------------------- inventory

    @Test
    void stock_create_set_cas_and_the_reservation_guard() {
        assertCode(get(STOCK, R, JsonNode.class), 404, "NOT_FOUND");
        ResponseEntity<JsonNode> created = put(STOCK, stock(50, null), W);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("onHand").asLong()).isEqualTo(50);
        assertThat(created.getBody().get("available").asLong()).isEqualTo(50);
        assertThat(created.getBody().get("active").asBoolean()).isTrue();
        assertCode(put(STOCK, stock(60, null), W), 409, "STALE_VERSION");
        ResponseEntity<JsonNode> set = put(STOCK, stock(40, 1L), W);
        assertThat(set.getStatusCode().value()).isEqualTo(200);
        assertThat(set.getBody().get("version").asLong()).isEqualTo(2);
        assertCode(put(STOCK, stock(45, 1L), W), 409, "STALE_VERSION");

        db.getCollection("inventory").updateOne(Filters.and(Filters.eq("sku_id", SKU), Filters.eq("fulfillment_location_id", "FL-1")),
                Updates.set("reserved", 30L));
        assertCode(put(STOCK, stock(20, 2L), W), 422, "INVALID_INVENTORY");               // below live reservations
        ResponseEntity<JsonNode> ok = put(STOCK, stock(30, 2L), W);
        assertThat(ok.getBody().get("reserved").asLong()).as("admin set never touches reserved").isEqualTo(30);
        assertThat(ok.getBody().get("available").asLong()).isEqualTo(0);
    }

    @Test
    void stock_validation_auth_and_lifecycle() {
        String loc2 = "/api/v1/admin/inventory/" + SKU + "/FL-2";
        assertThat(put(loc2, stock(5, null), null).getStatusCode().value()).isEqualTo(401);
        assertThat(put(loc2, stock(5, null), R).getStatusCode().value()).isEqualTo(403);
        assertCode(put("/api/v1/admin/inventory/TZP-NOPE/FL-2", stock(5, null), W), 404, "NOT_FOUND");
        assertCode(put(loc2, stock(-1, null), W), 422, "INVALID_INVENTORY");
        assertCode(put(loc2, stock(2_000_000, null), W), 422, "INVALID_INVENTORY");
        assertCode(put(loc2, Map.of("onHand", 5), W), 422, "INVALID_INVENTORY");
        assertCode(put("/api/v1/admin/inventory/" + SKU + "/" + "L".repeat(200), stock(5, null), W), 422, "INVALID_INVENTORY");
        assertCode(get(loc2, R, JsonNode.class), 404, "NOT_FOUND");
        assertThat(db.getCollection("inventory").countDocuments(Filters.eq("fulfillment_location_id", "FL-2"))).isZero();

        assertThat(put(loc2, stock(5, null), W).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<JsonNode> off = post(loc2 + "/deactivate", Map.of("expectedVersion", 1), W, JsonNode.class);
        assertThat(off.getBody().get("active").asBoolean()).isFalse();
        assertThat(off.getBody().get("onHand").asLong()).as("counters survive delisting").isEqualTo(5);
        assertCode(post(loc2 + "/deactivate", Map.of("expectedVersion", 1), W, JsonNode.class), 409, "STALE_VERSION");
        assertCode(post(loc2 + "/activate", Map.of(), W, JsonNode.class), 422, "INVALID_INVENTORY");
        assertCode(post("/api/v1/admin/inventory/" + SKU + "/FL-9/activate", Map.of("expectedVersion", 1), W, JsonNode.class), 404, "NOT_FOUND");
        assertThat(post(loc2 + "/activate", Map.of("expectedVersion", 2), W, JsonNode.class).getBody().get("active").asBoolean()).isTrue();
        assertThat(post(loc2 + "/activate", Map.of("expectedVersion", 3), R, JsonNode.class).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void stock_writes_are_attributed_to_the_authenticated_actor() {
        String loc3 = "/api/v1/admin/inventory/" + SKU + "/FL-3";
        put(loc3, stock(9, null), W);
        post(loc3 + "/deactivate", Map.of("expectedVersion", 1), W, JsonNode.class);
        List<Document> events = db.getCollection("product_events").find(Filters.eq("product_id", SKU)).into(new java.util.ArrayList<>());
        List<Document> stockEvents = events.stream().filter(e -> e.toJson().contains("FL-3")).toList();
        assertThat(stockEvents).hasSize(2);
        assertThat(stockEvents).allSatisfy(e -> {
            assertThat(ActorDocuments.fromEvent(e)).as("attributed").isPresent();
            assertThat(e.toJson()).doesNotContain("cms-test-token");
        });
    }
}
