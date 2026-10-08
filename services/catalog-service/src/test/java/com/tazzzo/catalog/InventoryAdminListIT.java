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
        // a sku id containing the old cursor separator (product ids only need the TZP- prefix): IN_STOCK
        db.getCollection("inventory").insertOne(raw("TZP-INV-5|X", "FL-1", 20, 0).append("low_stock_threshold", 5L).append("active", true));
        // legacy/corrupt rows: no threshold; reserved > on_hand; no active flag (valid otherwise -> INACTIVE)
        db.getCollection("inventory").insertOne(raw("TZP-INV-6", "FL-C", 9, 0).append("active", true));
        db.getCollection("inventory").insertOne(raw("TZP-INV-7", "FL-C", 1, 3).append("low_stock_threshold", 5L).append("active", true));
        db.getCollection("inventory").insertOne(raw("TZP-INV-8", "FL-C", 4, 0).append("low_stock_threshold", 5L));
        // ids that cannot be positioned (no sku_id; a numeric location) are out of the scan; a string counter is skipped and
        // never fails a state filter
        Document noSku = raw("x", "FL-C", 3, 0).append("low_stock_threshold", 5L).append("active", true);
        noSku.remove("sku_id");
        db.getCollection("inventory").insertOne(noSku);
        db.getCollection("inventory").insertOne(raw("TZP-INV-77", "FL-C", 3, 0).append("fulfillment_location_id", 42).append("low_stock_threshold", 5L).append("active", true));
        db.getCollection("inventory").insertOne(raw("TZP-INV-9", "FL-C", 3, 0).append("on_hand", "x").append("low_stock_threshold", 5L).append("active", true));
        // a valid row whose location is 128 three-byte characters: the cursor positioned on it is long and must still decode
        db.getCollection("inventory").insertOne(raw("TZP-INV-0", LONG_LOC, 20, 0).append("low_stock_threshold", 5L).append("active", true));
    }

    static final String LONG_LOC = "仓".repeat(128);

    private static Document raw(String sku, String loc, long onHand, long reserved) {
        return new Document("sku_id", sku).append("fulfillment_location_id", loc).append("on_hand", onHand).append("reserved", reserved)
                .append("max_purchasable", 10L).append("version", 1L);
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

    /** Every row, following nextCursor page by page; fails rather than looping if a cursor does not advance. */
    private List<String> walk(String query, int limit) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int pages = 0; pages < 50; pages++) {
            String q = LIST + "?limit=" + limit + query + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<JsonNode> r = get(q, R, JsonNode.class);
            assertThat(r.getStatusCode().value()).as(q + " -> " + r.getBody()).isEqualTo(200);
            all.addAll(keys(r.getBody()));
            JsonNode next = r.getBody().get("nextCursor");
            if (next.isNull()) return all;
            assertThat(next.asText()).as("the cursor advances").isNotEqualTo(cursor).matches("[A-Za-z0-9_-]+");
            cursor = next.asText();
        }
        throw new AssertionError("paging did not terminate");
    }

    static final List<String> ALL = List.of("TZP-INV-0@" + LONG_LOC + ":IN_STOCK", "TZP-INV-1@FL-1:IN_STOCK", "TZP-INV-1@FL-2:LOW_STOCK", "TZP-INV-2@FL-1:LOW_STOCK",
            "TZP-INV-2@FL-2:LOW_STOCK", "TZP-INV-3@FL-1:OUT_OF_STOCK", "TZP-INV-4@FL-1:INACTIVE", "TZP-INV-5|X@FL-1:IN_STOCK",
            "TZP-INV-8@FL-C:INACTIVE");

    @Test
    void lists_every_row_once_in_key_order_with_an_opaque_cursor_at_any_page_size() {
        for (int limit : List.of(1, 2, 3, 4, 200)) {
            assertThat(walk("", limit)).as("limit " + limit + ": every valid row exactly once, corrupt rows left out").containsExactlyElementsOf(ALL);
        }
        JsonNode p1 = get(LIST + "?limit=4", R, JsonNode.class).getBody();
        assertThat(keys(p1)).containsExactlyElementsOf(ALL.subList(0, 4));
        assertThat(keys(get(LIST + "?limit=1&cursor=" + p1.get("nextCursor").asText(), R, JsonNode.class).getBody())).containsExactly(ALL.get(4));
        String longCursor = get(LIST + "?limit=1", R, JsonNode.class).getBody().get("nextCursor").asText();
        assertThat(longCursor.length()).as("positioned on the long-location row").isGreaterThan(400);
        assertThat(keys(get(LIST + "?limit=1&cursor=" + longCursor, R, JsonNode.class).getBody())).containsExactly(ALL.get(1));
        // a page whose only rows are corrupt is empty but still moves on
        JsonNode beforeCorrupt = get(LIST + "?limit=8", R, JsonNode.class).getBody();
        JsonNode corruptPage = get(LIST + "?limit=2&cursor=" + beforeCorrupt.get("nextCursor").asText(), R, JsonNode.class).getBody();
        assertThat(keys(corruptPage)).isEmpty();
        assertThat(corruptPage.get("nextCursor").isNull()).isFalse();
    }

    @Test
    void the_location_and_state_filters_use_the_same_arithmetic_as_the_point_read() {
        assertThat(walk("&location=FL-1", 2)).containsExactly("TZP-INV-1@FL-1:IN_STOCK", "TZP-INV-2@FL-1:LOW_STOCK",
                "TZP-INV-3@FL-1:OUT_OF_STOCK", "TZP-INV-4@FL-1:INACTIVE", "TZP-INV-5|X@FL-1:IN_STOCK");
        assertThat(keys(get(LIST + "?state=LOW_STOCK", R, JsonNode.class).getBody()))
                .as("available = on_hand - reserved, not on_hand: row 1 @ FL-2 is low because 5 of 7 are reserved")
                .containsExactly("TZP-INV-1@FL-2:LOW_STOCK", "TZP-INV-2@FL-1:LOW_STOCK", "TZP-INV-2@FL-2:LOW_STOCK");
        assertThat(keys(get(LIST + "?state=IN_STOCK", R, JsonNode.class).getBody()))
                .as("available == threshold is LOW, not IN; a row without a threshold is left out, not a 500")
                .containsExactly("TZP-INV-0@" + LONG_LOC + ":IN_STOCK", "TZP-INV-1@FL-1:IN_STOCK", "TZP-INV-5|X@FL-1:IN_STOCK");
        assertThat(keys(get(LIST + "?state=OUT_OF_STOCK", R, JsonNode.class).getBody())).as("an inactive row at 0 is INACTIVE, not OUT_OF_STOCK")
                .containsExactly("TZP-INV-3@FL-1:OUT_OF_STOCK");
        assertThat(keys(get(LIST + "?state=INACTIVE", R, JsonNode.class).getBody())).as("not active = INACTIVE, a missing flag included")
                .containsExactly("TZP-INV-4@FL-1:INACTIVE", "TZP-INV-8@FL-C:INACTIVE");
        assertThat(keys(get(LIST + "?state=LOW_STOCK&location=FL-2", R, JsonNode.class).getBody())).containsExactly("TZP-INV-1@FL-2:LOW_STOCK", "TZP-INV-2@FL-2:LOW_STOCK");
        assertThat(keys(get(LIST + "?location=FL-9", R, JsonNode.class).getBody())).isEmpty();
        // the four states partition the valid rows exactly as the unfiltered list labels them
        List<String> union = new ArrayList<>();
        for (String st : List.of("IN_STOCK", "LOW_STOCK", "OUT_OF_STOCK", "INACTIVE")) union.addAll(walk("&state=" + st, 200));
        assertThat(union).containsExactlyInAnyOrderElementsOf(ALL);
        // every list row equals the point read, field by field
        for (JsonNode i : get(LIST + "?location=FL-1", R, JsonNode.class).getBody().get("items")) {
            if (i.get("skuId").asText().contains("|")) continue;   // no product/point-read route for the raw row
            JsonNode one = get("/api/v1/admin/inventory/" + i.get("skuId").asText() + "/" + i.get("fulfillmentLocationId").asText(), R, JsonNode.class).getBody();
            for (String f : List.of("skuId", "fulfillmentLocationId", "onHand", "reserved", "available", "lowStockThreshold", "maxPurchasable", "version", "active")) {
                assertThat(i.get(f)).as(i.get("skuId") + " " + f).isEqualTo(one.get(f));
            }
        }
    }

    @Test
    void authorisation_and_a_closed_query_grammar() {
        assertThat(get(LIST, null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get(LIST, W, JsonNode.class).getStatusCode().value()).as("a writer may read").isEqualTo(200);
        for (String bad : List.of("?limit=0", "?limit=201", "?limit=abc", "?limit=99999999999", "?limit=1&limit=2", "?state=SOLD_OUT",
                "?state=", "?stat=LOW_STOCK", "?cursor=nobar", "?cursor=" + "A".repeat(1101), "?cursor=MTA6YWI", "?location=" + "L".repeat(129))) {
            ResponseEntity<JsonNode> r = get(LIST + bad, R, JsonNode.class);
            assertThat(r.getStatusCode().value()).as(bad + " -> " + r.getBody()).isEqualTo(422);
            assertThat(r.getBody().at("/error/code").asText()).isEqualTo("INVALID_INVENTORY");
            assertThat(r.getBody().toString()).as("no internals in the message").doesNotContain("java.").doesNotContain("Exception");
        }
        assertThat(get(LIST + "?limit=200", R, JsonNode.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void the_admin_contract_names_the_stock_list_shapes() throws Exception {
        JsonNode spec = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/openapi.json")));
        JsonNode op = spec.at("/paths/~1api~1v1~1admin~1inventory/get");
        assertThat(op.get("operationId").asText()).isEqualTo("listStock");
        assertThat(op.at("/responses/200/content/*~1*/schema/$ref").asText()).isEqualTo("#/components/schemas/StockListPage");
        assertThat(spec.at("/components/schemas/StockListRow/properties/stockState").isMissingNode()).isFalse();
    }
}
