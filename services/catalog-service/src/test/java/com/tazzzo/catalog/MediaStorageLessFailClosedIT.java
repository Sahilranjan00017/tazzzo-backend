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
@org.springframework.context.annotation.Import(MediaStorageLessFailClosedIT.NonDev.class)
class MediaStorageLessFailClosedIT extends AbstractApiIT {

    /** What the wiring builds when tazzzo.migration.environment is not local/test/dev (the property itself would trip the datastore contract). */
    @org.springframework.boot.test.context.TestConfiguration
    static class NonDev {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.tazzzo.media.MediaIngestVerifier nonDevVerifier(com.tazzzo.media.MediaStorage storage, com.tazzzo.media.MediaUploadPolicy policy) {
            return new com.tazzzo.media.MediaIngestVerifier(storage, policy, 50_000_000L, 20_000, false);
        }
    }

    static final String SKU = "TZP-MED-3";
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
        m.put("internalKey", "med|3");
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
    void outside_local_test_dev_a_new_reference_without_storage_is_refused_but_clearing_is_allowed() {
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", "a1");
        asset.put("assetKey", "seed/x.jpg");
        asset.put("role", "PRIMARY");
        asset.put("sortOrder", 0);
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/v1/admin/media/product/" + SKU), HttpMethod.PUT,
                new HttpEntity<>(Map.of("assets", List.of(asset)), headers("cms-test-token")), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("MEDIA_STORAGE_NOT_CONFIGURED");
        assertThat(db.getCollection("media_refs").countDocuments()).isZero();
        ResponseEntity<JsonNode> clear = rest.exchange(url("/api/v1/admin/media/product/" + SKU), HttpMethod.PUT,
                new HttpEntity<>(Map.of("assets", List.of()), headers("cms-test-token")), JsonNode.class);
        assertThat(clear.getStatusCode().value()).isEqualTo(201);
    }
}
