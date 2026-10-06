package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.TestActors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

/** PR-T: bulk price/stock import: whole-file validation, dry run, per-row CAS outcomes, attribution, bounds and auth. */
class BulkImportIT extends AbstractApiIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final List<String> SKUS = List.of("TZP-BLK-1", "TZP-BLK-2", "TZP-BLK-3");
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        int n = 0;
        for (String sku : SKUS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sku);
            m.put("productType", "single");
            m.put("identityType", "internal");
            m.put("internalKey", "blk|" + (++n));
            m.put("brandCode", "BR-BLK");
            m.put("title", "Bulk " + sku);
            m.put("verticalId", "TZV-000001");
            m.put("releaseId", "0.9.0");
            m.put("classificationStatus", "provisional");
            m.put("attributes", Map.of("pack_size", n, "pack_unit", "kg"));
            m.put("evidenceRefs", List.of());
            assertThat(post("/api/v1/products", m, W, JsonNode.class).getStatusCode().value()).isEqualTo(201);
        }
    }

    static Map<String, Object> price(String sku, Object selling, Object mrp, Long expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", sku);
        if (selling != null) m.put("sellingPricePaise", selling);
        if (mrp != null) m.put("mrpPaise", mrp);
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    static Map<String, Object> stock(String sku, String loc, long onHand, Long expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", sku);
        m.put("locationId", loc);
        m.put("onHand", onHand);
        m.put("lowStockThreshold", 2);
        m.put("maxPurchasable", 6);
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    static Map<String, Object> file(boolean dryRun, List<?> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dryRun", dryRun);
        m.put("rows", rows);
        return m;
    }

    long count(String collection) {
        return db.getCollection(collection).countDocuments();
    }

    long imports() {
        return db.getCollection("domain_events").countDocuments(new Document("aggregate_type", "bulk_import"));
    }

    @Test
    void a_price_file_validates_as_a_whole_dry_runs_then_applies_with_per_row_cas() {
        long pricesBefore = count("price_current"), runsBefore = imports();
        // dry run: a report, no writes
        ResponseEntity<JsonNode> dry = post("/api/v1/admin/imports/prices", file(true, List.of(
                price("TZP-BLK-1", 9900, 12000, null), price("TZP-BLK-2", 500, 500, null))), W, JsonNode.class);
        assertThat(dry.getStatusCode().value()).as(String.valueOf(dry.getBody())).isEqualTo(200);
        assertThat(dry.getBody().get("dryRun").asBoolean()).isTrue();
        assertThat(dry.getBody().get("applied").asInt()).isZero();
        assertThat(dry.getBody().at("/results/1/outcome").asText()).isEqualTo("VALID");
        assertThat(count("price_current")).isEqualTo(pricesBefore);
        assertThat(imports()).isEqualTo(runsBefore);

        // apply
        ResponseEntity<JsonNode> applied = post("/api/v1/admin/imports/prices", file(false, List.of(
                price("TZP-BLK-1", 9900, 12000, null), price("TZP-BLK-2", 500, 500, null))), W, JsonNode.class);
        assertThat(applied.getStatusCode().value()).isEqualTo(200);
        assertThat(applied.getBody().get("applied").asInt()).isEqualTo(2);
        assertThat(applied.getBody().at("/results/0/outcome").asText()).isEqualTo("APPLIED");
        assertThat(applied.getBody().at("/results/0/version").asLong()).isEqualTo(1);
        assertThat(applied.getBody().get("importId").asText()).matches("IMP-[0-9a-f]{24}");
        assertThat(get("/api/v1/admin/prices/TZP-BLK-1", R, JsonNode.class).getBody().get("sellingPricePaise").asLong()).isEqualTo(9900);

        // second run: rows are independent; a stale row fails alone
        ResponseEntity<JsonNode> second = post("/api/v1/admin/imports/prices", file(false, List.of(
                price("TZP-BLK-1", 9500, 12000, 1L), price("TZP-BLK-2", 450, 500, 7L))), W, JsonNode.class);
        assertThat(second.getBody().get("applied").asInt()).isEqualTo(1);
        assertThat(second.getBody().get("failed").asInt()).isEqualTo(1);
        assertThat(second.getBody().at("/results/1/outcome").asText()).isEqualTo("FAILED");
        assertThat(second.getBody().at("/results/1/code").asText()).isEqualTo("STALE_VERSION");
        assertThat(get("/api/v1/admin/prices/TZP-BLK-1", R, JsonNode.class).getBody().get("sellingPricePaise").asLong()).isEqualTo(9500);
        assertThat(get("/api/v1/admin/prices/TZP-BLK-2", R, JsonNode.class).getBody().get("sellingPricePaise").asLong()).isEqualTo(500);

        // one summary audit row per applied run, attributed, with the counts
        assertThat(imports()).isEqualTo(runsBefore + 2);
        Document summary = db.getCollection("domain_events").find(new Document("aggregate_type", "bulk_import")
                .append("aggregate_id", second.getBody().get("importId").asText())).first();
        assertThat(summary.get("detail", Document.class).getInteger("applied")).isEqualTo(1);
        assertThat(summary.get("detail", Document.class).getInteger("failed")).isEqualTo(1);
        assertThat(summary.get("actor", Document.class).getString("id")).isNotBlank();
    }

    @Test
    void an_invalid_file_reports_every_bad_row_and_writes_nothing() {
        long prices = count("price_current"), runs = imports();
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("skuId", "TZP-BLK-3");
        ResponseEntity<JsonNode> res = post("/api/v1/admin/imports/prices", file(false, Arrays.asList(
                price("TZP-BLK-3", 100, 200, null),          // 0 valid
                price("TZP-BLK-3", 100, 200, null),          // 1 duplicate key
                price("TZP-BLK-2", 300, 200, null),          // 2 mrp below selling
                price("TZP-NOPE-9", 100, 200, null),         // 3 unknown product
                missing,                                     // 4 missing amounts
                price("TZP-BLK-1", -1, 200, null))), W, JsonNode.class);   // 5 negative
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(422);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("INVALID_IMPORT");
        assertThat(res.getBody().at("/error/request_id").asText()).startsWith("req_");
        JsonNode errors = res.getBody().get("rowErrors");
        assertThat(errors).hasSize(5);
        assertThat(errors.findValuesAsText("row")).containsExactly("1", "2", "3", "4", "5");
        assertThat(errors.findValuesAsText("code")).containsExactly("DUPLICATE_ROW", "INVALID_ROW", "UNKNOWN_PRODUCT",
                "INVALID_ROW", "INVALID_ROW");
        assertThat(count("price_current")).isEqualTo(prices);
        assertThat(imports()).isEqualTo(runs);
    }

    @Test
    void a_stock_file_applies_per_location_and_refuses_bad_quantities() {
        ResponseEntity<JsonNode> bad = post("/api/v1/admin/imports/inventory", file(false, List.of(
                stock("TZP-BLK-1", "FL-1", 10, null), stock("TZP-BLK-1", "FL-1", 4, null), stock("TZP-BLK-2", "FL-1", -3, null))),
                W, JsonNode.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(bad.getBody().get("rowErrors").findValuesAsText("code")).containsExactly("DUPLICATE_ROW", "INVALID_ROW");
        assertThat(count("inventory")).isZero();

        ResponseEntity<JsonNode> ok = post("/api/v1/admin/imports/inventory", file(false, List.of(
                stock("TZP-BLK-1", "FL-1", 10, null), stock("TZP-BLK-1", "FL-2", 4, null), stock("TZP-BLK-2", "FL-1", 0, null))),
                W, JsonNode.class);
        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
        assertThat(ok.getBody().get("applied").asInt()).isEqualTo(3);
        assertThat(ok.getBody().at("/results/1/key").asText()).isEqualTo("TZP-BLK-1|FL-2");
        assertThat(get("/api/v1/admin/inventory/TZP-BLK-1/FL-2", R, JsonNode.class).getBody().get("onHand").asLong()).isEqualTo(4);
    }

    @Test
    void bounds_and_authorisation() {
        assertThat(post("/api/v1/admin/imports/prices", file(false, List.of()), W, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        assertThat(post("/api/v1/admin/imports/prices", Map.of("dryRun", true), W, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(price("TZP-BLK-1", 100, 200, null));
        ResponseEntity<JsonNode> big = post("/api/v1/admin/imports/prices", file(true, tooMany), W, JsonNode.class);
        assertThat(big.getStatusCode().value()).isEqualTo(422);
        assertThat(big.getBody().at("/error/message").asText()).isEqualTo("rows must contain 1..500 entries");
        assertThat(big.getBody().get("rowErrors")).as("the cap is checked before any row").isEmpty();

        List<Map<String, Object>> one = List.of(price("TZP-BLK-3", 100, 200, null));
        assertThat(post("/api/v1/admin/imports/prices", file(false, one), R, JsonNode.class).getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/admin/imports/inventory", file(false, List.of(stock("TZP-BLK-3", "FL-1", 1, null))), R,
                JsonNode.class).getStatusCode().value()).isEqualTo(403);
        assertThat(post("/api/v1/admin/imports/prices", file(false, one), null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
    }
}
