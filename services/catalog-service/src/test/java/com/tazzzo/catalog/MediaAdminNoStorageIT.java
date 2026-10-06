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

/** The default deployment: NO object storage configured. Uploads are refused honestly; set writes stay metadata-only. */
class MediaAdminNoStorageIT extends AbstractApiIT {

    static final String SKU = "TZP-MED-2";
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
        m.put("internalKey", "med|2");
        m.put("brandCode", "BR-MED");
        m.put("title", "Media " + SKU);
        m.put("verticalId", "TZV-000001");
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products", m, "cms-test-token", JsonNode.class).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void uploads_are_503_with_a_stable_code_and_set_writes_are_unverified_metadata() {
        ResponseEntity<JsonNode> up = post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 10), "cms-test-token", JsonNode.class);
        assertThat(up.getStatusCode().value()).isEqualTo(503);
        assertThat(up.getBody().at("/error/code").asText()).isEqualTo("MEDIA_STORAGE_NOT_CONFIGURED");

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", "a1");
        asset.put("assetKey", "seed/x.jpg");
        asset.put("role", "PRIMARY");
        asset.put("sortOrder", 0);
        for (String hostile : new String[]{"../etc/passwd", "/abs/key.jpg", "a//b.jpg", "a b.jpg", "a%2e%2e/b.jpg", "https://evil.example/x.jpg"}) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("assetId", "h1");
            bad.put("assetKey", hostile);
            bad.put("role", "PRIMARY");
            bad.put("sortOrder", 0);
            ResponseEntity<JsonNode> refused = rest.exchange(url("/api/v1/admin/media/product/" + SKU), HttpMethod.PUT,
                    new HttpEntity<>(Map.of("assets", List.of(bad)), headers("cms-test-token")), JsonNode.class);
            assertThat(refused.getStatusCode().value()).as(hostile).isEqualTo(422);
        }
        assertThat(db.getCollection("media_refs").countDocuments()).isZero();
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/v1/admin/media/product/" + SKU), HttpMethod.PUT,
                new HttpEntity<>(Map.of("assets", List.of(asset)), headers("cms-test-token")), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody().at("/assets/0/assetKey").asText()).isEqualTo("seed/x.jpg");
    }
}
