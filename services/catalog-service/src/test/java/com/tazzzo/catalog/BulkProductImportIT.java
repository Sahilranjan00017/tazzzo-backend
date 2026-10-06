package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Bulk product catalogue import: whole-file validation, dry run, apply through mint, UNCHANGED re-submits, 500 SKUs, auth. */
@Timeout(300)
class BulkProductImportIT extends AbstractApiIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String PATH = "/api/v1/admin/imports/products";
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired com.tazzzo.catalog.schema.DiscriminatingAttributeRegistry registry;

    /** A vertical no other test class ratifies (the registry is a context-wide singleton): keys derive here. */
    static final String KEYED = "TZV-000004";

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
        registry.ratify(new com.tazzzo.catalog.schema.Ratification(KEYED, "bulk-import-test", List.of("pack"), Map.of()));
    }

    @org.junit.jupiter.api.AfterAll
    void forgetRatification() {
        registry.clear();
    }

    /** A valid internal-identity single product; {@code n} makes its identity (pack size) unique. */
    static Map<String, Object> row(String id, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", "bulk|" + id);
        m.put("brandCode", "BR-BULK");
        m.put("title", "Bulk rice " + n + " kg");
        m.put("verticalId", "TZV-000001");
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", n, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        return m;
    }

    static Map<String, Object> gtinRow(String id, int n, String gtin) {
        Map<String, Object> m = row(id, n);
        m.put("identityType", "gtin");
        m.remove("internalKey");
        m.put("gtins", List.of(Map.of("value", gtin, "market", "IN")));
        return m;
    }

    static Map<String, Object> file(boolean dryRun, List<?> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dryRun", dryRun);
        m.put("rows", rows);
        return m;
    }

    long products() {
        return db.getCollection("products").countDocuments();
    }

    long productEvents() {
        return db.getCollection("product_events").countDocuments();
    }

    long summaries() {
        return db.getCollection("domain_events").countDocuments(new Document("aggregate_type", "bulk_import"));
    }

    @Test
    void dry_run_then_apply_then_resubmit_is_unchanged() {
        List<Map<String, Object>> rows = List.of(row("TZP-BP-001", 901), row("TZP-BP-002", 902),
                gtinRow("TZP-BP-003", 903, "8901234567890"));
        long p0 = products(), e0 = productEvents(), s0 = summaries();

        ResponseEntity<JsonNode> dry = post(PATH, file(true, rows), W, JsonNode.class);
        assertThat(dry.getStatusCode().value()).as(String.valueOf(dry.getBody())).isEqualTo(200);
        assertThat(dry.getBody().get("results").findValuesAsText("outcome")).containsExactly("VALID", "VALID", "VALID");
        assertThat(products()).isEqualTo(p0);
        assertThat(productEvents()).isEqualTo(e0);
        assertThat(summaries()).isEqualTo(s0);

        ResponseEntity<JsonNode> applied = post(PATH, file(false, rows), W, JsonNode.class);
        assertThat(applied.getStatusCode().value()).as(String.valueOf(applied.getBody())).isEqualTo(200);
        assertThat(applied.getBody().get("applied").asInt()).isEqualTo(3);
        assertThat(products()).isEqualTo(p0 + 3);
        JsonNode read = get("/api/v1/products/TZP-BP-002", R, JsonNode.class).getBody();
        assertThat(read.get("title").asText()).isEqualTo("Bulk rice 902 kg");
        Document event = db.getCollection("product_events").find(new Document("product_id", "TZP-BP-001")).first();
        assertThat(event).as("attributed product event, same as a single create").isNotNull();
        assertThat(event.get("actor", Document.class).getString("id")).isNotBlank();
        assertThat(summaries()).isEqualTo(s0 + 1);

        long e1 = productEvents();
        ResponseEntity<JsonNode> again = post(PATH, file(false, rows), W, JsonNode.class);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("unchanged").asInt()).isEqualTo(3);
        assertThat(again.getBody().get("applied").asInt()).isZero();
        assertThat(again.getBody().get("results").findValuesAsText("outcome")).containsOnly("UNCHANGED");
        assertThat(products()).isEqualTo(p0 + 3);
        assertThat(productEvents()).as("a re-submit rewrites nothing").isEqualTo(e1);
    }

    @Test
    void an_invalid_file_reports_every_bad_row_and_writes_nothing() {
        post(PATH, file(false, List.of(row("TZP-BP-EX1", 801))), W, JsonNode.class);   // an existing product
        long p0 = products(), e0 = productEvents(), s0 = summaries();
        Map<String, Object> badVertical = row("TZP-BP-101", 811);
        badVertical.put("verticalId", "TZV-999999");
        Map<String, Object> noTitle = row("TZP-BP-106", 816);
        noTitle.remove("title");
        Map<String, Object> changed = row("TZP-BP-EX1", 801);
        changed.put("title", "a different title");
        Map<String, Object> badAttr = row("TZP-BP-108", 818);
        badAttr.put("attributes", Map.of("pack_size", 818, "pack_unit", "kg", "not_in_schema", "x"));
        Map<String, Object> badStatus = row("TZP-BP-109", 819);
        badStatus.put("classificationStatus", "maybe");                 // only the products $jsonSchema refuses this
        List<Map<String, Object>> rows = List.of(
                row("TZP-BP-100", 810),                         // 0 valid
                badVertical,                                    // 1 vertical does not exist
                gtinRow("TZP-BP-102", 812, "8901234567893"),    // 2 bad GTIN check digit
                row("TZP-BP-100", 813),                         // 3 duplicate id in the file
                gtinRow("TZP-BP-104", 814, "8901234567890"),    // 4 valid gtin
                gtinRow("TZP-BP-105", 815, "8901234567890"),    // 5 duplicate gtin in the file
                noTitle,                                        // 6 missing title
                changed,                                        // 7 existing id, different payload
                badAttr,                                        // 8 attribute outside the schema
                badStatus);                                     // 9 fails the collection validator
        ResponseEntity<JsonNode> res = post(PATH, file(false, rows), W, JsonNode.class);
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(422);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("INVALID_IMPORT");
        JsonNode errors = res.getBody().get("rowErrors");
        assertThat(errors.findValuesAsText("row")).containsExactly("1", "2", "3", "5", "6", "7", "8", "9");
        assertThat(errors.findValuesAsText("code")).containsExactly("INVALID_ROW", "INVALID_ROW", "DUPLICATE_ROW",
                "DUPLICATE_ROW", "INVALID_ROW", "CONFLICT", "INVALID_ROW", "INVALID_ROW");
        assertThat(errors.get(7).get("message").asText()).isEqualTo("row fails the products document contract (validator)");
        assertThat(db.getCollection("products").countDocuments(new Document("_id", "TZP-BP-109")))
                .as("the validator probe's transaction was aborted").isZero();
        assertThat(products()).isEqualTo(p0);
        assertThat(productEvents()).isEqualTo(e0);
        assertThat(summaries()).isEqualTo(s0);
        assertThat(db.getCollection("identity_keys").countDocuments(new Document("_id", "bulk|TZP-BP-100"))).isZero();
    }

    @Test
    void identity_already_owned_by_another_product_is_a_conflict() {
        post(PATH, file(false, List.of(gtinRow("TZP-BP-201", 821, "8901234567807"))), W, JsonNode.class);
        Map<String, Object> sameKey = row("TZP-BP-203", 823);
        sameKey.put("internalKey", "bulk|TZP-BP-201-key");
        post(PATH, file(false, List.of(sameKey)), W, JsonNode.class);
        Map<String, Object> keyThief = row("TZP-BP-204", 824);
        keyThief.put("internalKey", "bulk|TZP-BP-201-key");
        ResponseEntity<JsonNode> res = post(PATH, file(false, List.of(
                gtinRow("TZP-BP-202", 822, "8901234567807"), keyThief)), W, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().get("rowErrors").findValuesAsText("code")).containsExactly("CONFLICT", "CONFLICT");
        assertThat(res.getBody().get("rowErrors").findValuesAsText("message"))
                .containsExactly("gtin 8901234567807 already belongs to another product", "internalKey already belongs to another product");
    }

    @Test
    void the_same_canonical_identity_twice_in_a_file_or_against_an_existing_product_is_refused() {
        Map<String, Object> first = row("TZP-BP-401", 841);
        first.put("verticalId", KEYED);
        assertThat(post(PATH, file(false, List.of(first)), W, JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(db.getCollection("canonical_keys").countDocuments(new Document("product_id", "TZP-BP-401")))
                .as("the ratified vertical derives a canonical key").isEqualTo(1);
        Map<String, Object> sameAsExisting = row("TZP-BP-402", 841);          // same brand + vertical + pack as 401
        sameAsExisting.put("verticalId", KEYED);
        Map<String, Object> a = row("TZP-BP-403", 843);
        a.put("verticalId", KEYED);
        Map<String, Object> b = row("TZP-BP-404", 843);                       // same identity as row 1 of this file
        b.put("verticalId", KEYED);
        ResponseEntity<JsonNode> res = post(PATH, file(false, List.of(sameAsExisting, a, b)), W, JsonNode.class);
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(422);
        assertThat(res.getBody().get("rowErrors").findValuesAsText("row")).containsExactly("0", "2");
        assertThat(res.getBody().get("rowErrors").findValuesAsText("code")).containsExactly("CONFLICT", "DUPLICATE_ROW");
        assertThat(db.getCollection("products").countDocuments(new Document("_id", "TZP-BP-403"))).isZero();
    }

    @Test
    void a_full_500_sku_catalogue_loads_in_one_call() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 500; i++) rows.add(row(String.format("TZP-5%05d", i), 1000 + i));
        long p0 = products();
        long t0 = System.nanoTime();
        ResponseEntity<JsonNode> res = post(PATH, file(false, rows), W, JsonNode.class);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("bulk_product_import_500_ms=" + ms);
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(200);
        assertThat(res.getBody().get("applied").asInt()).isEqualTo(500);
        assertThat(res.getBody().get("failed").asInt()).isZero();
        assertThat(products()).isEqualTo(p0 + 500);
        for (int i = 1; i <= 500; i += 49) {
            assertThat(get("/api/v1/products/" + String.format("TZP-5%05d", i), R, JsonNode.class).getStatusCode().value())
                    .isEqualTo(200);
        }
        assertThat(db.getCollection("products").countDocuments(new Document("_id",
                new Document("$regex", "^TZP-5\\d{5}$")))).isEqualTo(500);
    }

    @Test
    void bounds_and_authorisation() {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(row(String.format("TZP-6%05d", i), 2000 + i));
        ResponseEntity<JsonNode> big = post(PATH, file(true, tooMany), W, JsonNode.class);
        assertThat(big.getStatusCode().value()).isEqualTo(422);
        assertThat(big.getBody().at("/error/message").asText()).isEqualTo("rows must contain 1..500 entries");
        assertThat(post(PATH, file(false, List.of()), W, JsonNode.class).getStatusCode().value()).isEqualTo(422);
        List<Map<String, Object>> one = List.of(row("TZP-BP-301", 831));
        assertThat(post(PATH, file(false, one), R, JsonNode.class).getStatusCode().value()).isEqualTo(403);
        assertThat(post(PATH, file(false, one), null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        Map<String, Object> pack = row("TZP-BP-302", 832);
        pack.put("productType", "variant_pack");
        ResponseEntity<JsonNode> scope = post(PATH, file(true, List.of(pack)), W, JsonNode.class);
        assertThat(scope.getStatusCode().value()).isEqualTo(422);
        assertThat(scope.getBody().at("/rowErrors/0/message").asText()).contains("productType must be single");
    }
}
