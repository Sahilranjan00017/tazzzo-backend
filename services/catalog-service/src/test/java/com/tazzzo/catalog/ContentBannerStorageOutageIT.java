package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.media.MediaStorage;
import com.tazzzo.media.MediaStorageFailure;
import com.tazzzo.media.StoredObject;
import com.tazzzo.media.UploadTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A storage outage while a banner's image is verified is a 503 MEDIA_STORAGE_UNAVAILABLE, nothing is written, never a 500. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, ContentBannerStorageOutageIT.FailingStorage.class})
@ExtendWith(OutputCaptureExtension.class)
class ContentBannerStorageOutageIT extends AbstractApiIT {

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

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
    }

    @Test
    void an_unreachable_store_while_saving_a_banner_is_a_503_and_nothing_is_written(CapturedOutput log) {
        Map<String, Object> body = Map.of("type", "BANNER", "title", "Outage", "sort", 1, "audience", "BOTH",
                "payload", Map.of("imageAssetKey", "c/home/0b7c5a52-1f5e-4a43-9c3c-2b7d8e4f6a10.png", "link", "search:rice"));
        ResponseEntity<JsonNode> r = rest.exchange(url("/api/v1/admin/content/blocks"), HttpMethod.POST,
                new HttpEntity<>(body, headers("cms-test-token")), JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(503);
        assertThat(r.getBody().at("/error/code").asText()).isEqualTo("MEDIA_STORAGE_UNAVAILABLE");
        assertThat(r.getBody().toString()).doesNotContain("SdkClientException");
        assertThat(log.getOut()).contains("content_image_verify_storage_failure").doesNotContain("\tat org.springframework");
        assertThat(db.getCollection("content_blocks").countDocuments()).isZero();
    }
}
