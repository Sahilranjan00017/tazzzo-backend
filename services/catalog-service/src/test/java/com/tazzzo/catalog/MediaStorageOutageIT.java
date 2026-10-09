package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.media.MediaStorage;
import com.tazzzo.media.MediaStorageFailure;
import com.tazzzo.media.StoredObject;
import com.tazzzo.media.UploadTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A storage outage while a key is being verified is a 503 with its own code, one WARN line and no stack trace -- never a 500. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, MediaStorageOutageIT.FailingStorage.class})
@ExtendWith(OutputCaptureExtension.class)
class MediaStorageOutageIT extends AbstractApiIT {

    @TestConfiguration
    static class FailingStorage {
        @Bean
        @Primary
        MediaStorage failingStorage() {
            return new MediaStorage() {
                @Override public boolean enabled() { return true; }
                @Override public UploadTarget createUpload(String assetKey, String contentType, long sizeBytes) {
                    return new UploadTarget("PUT", "https://storage.example.test/" + assetKey, Map.of("Content-Type", contentType),
                            Instant.now().plusSeconds(300));
                }
                @Override public Optional<StoredObject> inspect(String assetKey) { throw new MediaStorageFailure("SdkClientException"); }
            };
        }
    }

    static final String SKU = "TZP-MED-5";
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
        m.put("internalKey", "med|5");
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
    void an_unreachable_store_is_a_503_with_a_stable_code_and_no_stack_trace(CapturedOutput log) {
        JsonNode target = post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 10), "cms-test-token", JsonNode.class).getBody();
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetId", "a1");
        asset.put("assetKey", target.get("assetKey").asText());
        asset.put("role", "PRIMARY");
        asset.put("sortOrder", 0);
        asset.put("contentType", "image/png");
        ResponseEntity<JsonNode> r = rest.exchange(url("/api/v1/admin/media/product/" + SKU), HttpMethod.PUT,
                new HttpEntity<>(Map.of("assets", List.of(asset)), headers("cms-test-token")), JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(503);
        assertThat(r.getBody().at("/error/code").asText()).isEqualTo("MEDIA_STORAGE_UNAVAILABLE");
        assertThat(r.getBody().toString()).doesNotContain("SdkClientException");
        assertThat(log.getOut()).contains("media_storage_unavailable type=SdkClientException")
                .doesNotContain("\tat org.springframework").doesNotContain("unhandled");
        assertThat(db.getCollection("media_refs").countDocuments()).as("nothing written").isZero();
    }
}
