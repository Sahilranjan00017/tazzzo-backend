package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
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

/**
 * The canonical product id grammar on the admin WRITE paths, over real HTTP and Mongo: create (single, bundle,
 * variant_pack components) and the normal bulk import accept and reject exactly the shared corpus. The cart and content
 * halves of the same corpus are in {@code ProductIdGrammarTest} and {@code CartHttpIT}.
 */
@Timeout(300)
class ProductIdGrammarIT extends AbstractApiIT {

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
    }

    /** Each test starts with no products or identities, so the shared corpus ids never collide across tests. */
    @org.junit.jupiter.api.BeforeEach
    void clean() {
        for (String c : List.of("products", "identity_keys", "work_queue", "product_events", "classification_history")) {
            db.getCollection(c).deleteMany(new org.bson.Document());
        }
    }

    static Map<String, Object> row(String id, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", "grammar|" + n);
        m.put("brandCode", "BR-GRAMMAR");
        m.put("title", "Grammar rice " + n + " kg");
        m.put("verticalId", "TZV-000001");
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", n, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        return m;
    }

    @Test
    void create_accepts_the_valid_corpus_preserving_case_and_rejects_the_invalid_corpus_with_400() {
        int n = 100;
        for (String id : ProductIdCorpus.VALID) {
            ResponseEntity<JsonNode> res = post("/api/v1/products", row(id, ++n), W, JsonNode.class);
            assertThat(res.getStatusCode().value()).as(id + " -> " + res.getBody()).isEqualTo(201);
            assertThat(res.getBody().get("id").asText()).as("no case normalisation").isEqualTo(id);
            assertThat(get("/api/v1/products/" + id, R, JsonNode.class).getBody().get("id").asText()).isEqualTo(id);
        }
        assertThat(get("/api/v1/products/TZP-med-3", R, JsonNode.class).getStatusCode().value())
                .as("TZP-Med-3 was created; TZP-med-3 is a different id").isEqualTo(404);

        long before = db.getCollection("products").countDocuments();
        for (String id : ProductIdCorpus.INVALID) {
            ResponseEntity<JsonNode> res = post("/api/v1/products", row(id, ++n), W, JsonNode.class);
            assertThat(res.getStatusCode().value()).as(id + " -> " + res.getBody()).isEqualTo(400);
            assertThat(res.getBody().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
            String message = res.getBody().at("/error/message").asText();
            assertThat(message).contains("id must match ^TZP-").doesNotContain("Exception").doesNotContain("at com.");
        }
        assertThat(db.getCollection("products").countDocuments()).as("nothing written").isEqualTo(before);
        assertThat(db.getCollection("identity_keys").countDocuments(new org.bson.Document("_id", "grammar|" + n)))
                .as("no identity claimed by a rejected create").isZero();
    }

    @Test
    void bundle_and_variant_pack_component_ids_follow_the_same_grammar() {
        int n = 300;
        for (String id : ProductIdCorpus.INVALID) {
            Map<String, Object> bundle = row("TZP-BUNDLE-" + ++n, n);
            bundle.put("productType", "bundle");
            bundle.put("bundleContents", List.of(Map.of("componentProductId", id, "qty", 1)));
            ResponseEntity<JsonNode> b = post("/api/v1/products", bundle, W, JsonNode.class);
            assertThat(b.getStatusCode().value()).as("bundle component " + id + " -> " + b.getBody()).isEqualTo(400);
            assertThat(b.getBody().at("/error/message").asText()).contains("componentProductId must match");

            Map<String, Object> pack = row("TZP-PACK-" + ++n, n);
            pack.put("productType", "variant_pack");
            pack.put("packOf", Map.of("componentProductId", id, "qty", 6));
            ResponseEntity<JsonNode> p = post("/api/v1/products", pack, W, JsonNode.class);
            assertThat(p.getStatusCode().value()).as("pack component " + id + " -> " + p.getBody()).isEqualTo(400);
            assertThat(p.getBody().at("/error/message").asText()).contains("packOf.componentProductId must match");
        }
        // a VALID but unknown component is not a grammar failure: it reaches the domain check (not 400 MALFORMED_REQUEST)
        for (String id : ProductIdCorpus.VALID) {
            Map<String, Object> bundle = row("TZP-BUNDLE-" + ++n, n);
            bundle.put("productType", "bundle");
            bundle.put("bundleContents", List.of(Map.of("componentProductId", id, "qty", 1)));
            ResponseEntity<JsonNode> b = post("/api/v1/products", bundle, W, JsonNode.class);
            assertThat(String.valueOf(b.getBody())).as("bundle component " + id).doesNotContain("must match");
        }
        assertThat(db.getCollection("products").countDocuments(new org.bson.Document("product_type", "bundle")
                .append("_id", new org.bson.Document("$regex", "^TZP-BUNDLE-")))).isZero();
        assertThat(db.getCollection("products").countDocuments(new org.bson.Document("product_type", "variant_pack"))).isZero();
    }

    @Test
    void the_normal_bulk_import_reports_each_invalid_id_as_an_explicit_row_error_and_accepts_the_valid_corpus() {
        List<Map<String, Object>> okRows = new ArrayList<>();
        int k = 600;
        for (String id : ProductIdCorpus.VALID) okRows.add(row(id, ++k));
        ResponseEntity<JsonNode> ok = post("/api/v1/admin/imports/products", Map.of("dryRun", true, "rows", okRows), W, JsonNode.class);
        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
        assertThat(ok.getBody().get("results").findValuesAsText("outcome")).hasSize(ProductIdCorpus.VALID.size()).containsOnly("VALID");

        List<Map<String, Object>> badRows = new ArrayList<>();
        for (String id : ProductIdCorpus.INVALID) badRows.add(row(id, ++k));
        ResponseEntity<JsonNode> bad = post("/api/v1/admin/imports/products", Map.of("dryRun", true, "rows", badRows), W, JsonNode.class);
        assertThat(bad.getStatusCode().value()).as(String.valueOf(bad.getBody())).isEqualTo(422);
        JsonNode errors = bad.getBody().get("rowErrors");
        assertThat(errors).hasSize(ProductIdCorpus.INVALID.size());
        for (int i = 0; i < errors.size(); i++) {
            assertThat(errors.get(i).get("row").asInt()).isEqualTo(i);
            assertThat(errors.get(i).get("code").asText()).isEqualTo("INVALID_ROW");
            assertThat(errors.get(i).get("message").asText()).startsWith("id must match ^TZP-");
        }
    }
}
