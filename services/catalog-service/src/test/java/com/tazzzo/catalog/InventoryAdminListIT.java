package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/admin/inventory}: the stock list and low-stock feed the CMS needs — keyset order and cursor, the
 * location filter, the server-derived state filter (the same arithmetic as the point read), auth, validation.
 */
class InventoryAdminListIT extends AbstractApiIT {

    static final String LIST = "/api/v1/admin/inventory";
    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        for (int i = 1; i <= 4; i++) product("TZP-INV-" + i);
        // FL-1: 1 in stock (20 > 5), 2 low (3 <= 5), 3 out (0), 4 inactive at 0 (would be OUT_OF_STOCK if active);
        // FL-2: 1 low by reservation (below), 2 low exactly AT the threshold (5 <= 5)
        stock("TZP-INV-1", "FL-1", 20); stock("TZP-INV-2", "FL-1", 3); stock("TZP-INV-3", "FL-1", 0); stock("TZP-INV-4", "FL-1", 0);
        stock("TZP-INV-1", "FL-2", 7); stock("TZP-INV-2", "FL-2", 5);
        ResponseEntity<JsonNode> off = post("/api/v1/admin/inventory/TZP-INV-4/FL-1/deactivate", Map.of("expectedVersion", 1), W, JsonNode.class);
        assertThat(off.getStatusCode().value()).as(String.valueOf(off.getBody())).isEqualTo(200);
        // a reservation makes "available" differ from on_hand: row 1 @ FL-2 has 7 on hand, 5 reserved -> available 2 -> LOW
        db.getCollection("inventory").updateOne(new Document("sku_id", "TZP-INV-1").append("fulfillment_location_id", "FL-2"),
                new Document("$set", new Document("reserved", 5L)));
    }

    private void product(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("productType", "single"); m.put("identityType", "internal"); m.put("internalKey", "inv|" + id);
        m.put("brandCode", "BR-INV"); m.put("title", "Inv " + id); m.put("verticalId", "TZV-000001"); m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional"); m.put("attributes", Map.of("pack_size", 1, "pack_unit", "kg")); m.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products", m, W, JsonNode.class).getStatusCode().value()).isEqualTo(201);
    }

    private void stock(String sku, String loc, long onHand) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("onHand", onHand); m.put("lowStockThreshold", 5); m.put("maxPurchasable", 10);
        ResponseEntity<JsonNode> r = rest.exchange(url("/api/v1/admin/inventory/" + sku + "/" + loc), HttpMethod.PUT, new HttpEntity<>(m, headers(W)), JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
    }

    private static List<String> keys(JsonNode page) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : page.get("items")) out.add(i.get("skuId").asText() + "@" + i.get("fulfillmentLocationId").asText() + ":" + i.get("stockState").asText());
        return out;
    }

    @Test
    void lists_every_row_in_key_order_with_a_keyset_cursor() {
        ResponseEntity<JsonNode> p1 = get(LIST + "?limit=4", R, JsonNode.class);
        assertThat(p1.getStatusCode().value()).as(String.valueOf(p1.getBody())).isEqualTo(200);
        assertThat(keys(p1.getBody())).containsExactly("TZP-INV-1@FL-1:IN_STOCK", "TZP-INV-1@FL-2:LOW_STOCK", "TZP-INV-2@FL-1:LOW_STOCK", "TZP-INV-2@FL-2:LOW_STOCK");
        assertThat(p1.getBody().get("nextCursor").asText()).isEqualTo("TZP-INV-2|FL-2");
        JsonNode p2 = get(LIST + "?limit=4&cursor=TZP-INV-2|FL-2", R, JsonNode.class).getBody();
        assertThat(keys(p2)).containsExactly("TZP-INV-3@FL-1:OUT_OF_STOCK", "TZP-INV-4@FL-1:INACTIVE");
        assertThat(p2.get("nextCursor").isNull()).isTrue();
        JsonNode row = p2.get("items").get(0);
        assertThat(row.get("available").asLong()).isZero();
        assertThat(row.get("version").asLong()).isEqualTo(1);
        assertThat(p2.get("items").get(1).get("active").asBoolean()).isFalse();
    }

    @Test
    void the_location_and_state_filters_use_the_same_arithmetic_as_the_point_read() {
        assertThat(keys(get(LIST + "?location=FL-1", R, JsonNode.class).getBody()))
                .containsExactly("TZP-INV-1@FL-1:IN_STOCK", "TZP-INV-2@FL-1:LOW_STOCK", "TZP-INV-3@FL-1:OUT_OF_STOCK", "TZP-INV-4@FL-1:INACTIVE");
        assertThat(keys(get(LIST + "?state=LOW_STOCK", R, JsonNode.class).getBody()))
                .as("available = on_hand - reserved, not on_hand: row 1 @ FL-2 is low because 5 of 7 are reserved")
                .containsExactly("TZP-INV-1@FL-2:LOW_STOCK", "TZP-INV-2@FL-1:LOW_STOCK", "TZP-INV-2@FL-2:LOW_STOCK");
        assertThat(keys(get(LIST + "?state=IN_STOCK", R, JsonNode.class).getBody()))
                .as("available == threshold is LOW, not IN")
                .containsExactly("TZP-INV-1@FL-1:IN_STOCK");
        assertThat(keys(get(LIST + "?state=OUT_OF_STOCK", R, JsonNode.class).getBody())).as("an inactive row at 0 is INACTIVE, not OUT_OF_STOCK")
                .containsExactly("TZP-INV-3@FL-1:OUT_OF_STOCK");
        assertThat(keys(get(LIST + "?state=INACTIVE", R, JsonNode.class).getBody())).containsExactly("TZP-INV-4@FL-1:INACTIVE");
        assertThat(keys(get(LIST + "?state=LOW_STOCK&location=FL-2", R, JsonNode.class).getBody())).containsExactly("TZP-INV-1@FL-2:LOW_STOCK", "TZP-INV-2@FL-2:LOW_STOCK");
        assertThat(keys(get(LIST + "?location=FL-9", R, JsonNode.class).getBody())).isEmpty();
        // the state in the list equals the point read's for every row
        for (JsonNode i : get(LIST, R, JsonNode.class).getBody().get("items")) {
            JsonNode one = get("/api/v1/admin/inventory/" + i.get("skuId").asText() + "/" + i.get("fulfillmentLocationId").asText(), R, JsonNode.class).getBody();
            assertThat(i.get("available").asLong()).isEqualTo(one.get("available").asLong());
            assertThat(i.get("version").asLong()).isEqualTo(one.get("version").asLong());
        }
    }

    @Test
    void authorisation_and_validation() {
        assertThat(get(LIST, null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get(LIST, W, JsonNode.class).getStatusCode().value()).as("a writer may read").isEqualTo(200);
        for (String bad : List.of("?limit=0", "?limit=201", "?state=SOLD_OUT", "?cursor=nobar", "?cursor=|FL-1", "?cursor=TZP-1|",
                "?location=" + "L".repeat(129), "?cursor=TZP-1|" + "L".repeat(129))) {
            ResponseEntity<JsonNode> r = get(LIST + bad, R, JsonNode.class);
            assertThat(r.getStatusCode().value()).as(bad + " -> " + r.getBody()).isEqualTo(422);
            assertThat(r.getBody().at("/error/code").asText()).isEqualTo("INVALID_INVENTORY");
        }
        assertThat(get(LIST + "?limit=200", R, JsonNode.class).getStatusCode().value()).isEqualTo(200);
    }
}
