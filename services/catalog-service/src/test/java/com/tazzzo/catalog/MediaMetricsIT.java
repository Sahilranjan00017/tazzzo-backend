package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.media.MediaMetrics;
import com.tazzzo.media.MediaStorage;
import com.tazzzo.media.MediaStorageFailure;
import com.tazzzo.media.StoredObject;
import com.tazzzo.media.UploadTarget;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Media presign / verify / write meters through the real admin flow (the MediaAdminIT fixture: real HTTP, real MongoDB, an
 * in-memory storage double), plus the proof that no outcome tag ever carries the owner id, the asset key or a content type.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, MediaMetricsIT.FakeStorage.class})
class MediaMetricsIT extends AbstractApiIT {

    static final Map<String, StoredObject> OBJECTS = new ConcurrentHashMap<>();
    static volatile boolean outage;
    /** How often the double threw: the test's HTTP client retries a 503 once, so requests sent != failures the server saw. */
    static final java.util.concurrent.atomic.AtomicInteger OUTAGE_HITS = new java.util.concurrent.atomic.AtomicInteger();
    static final byte[] JPEG = MediaAdminIT.JPEG;
    static final byte[] PNG = MediaAdminIT.PNG;

    @TestConfiguration
    static class FakeStorage {
        @Bean
        @Primary
        MediaStorage fakeStorage() {
            return new MediaStorage() {
                @Override public boolean enabled() { return true; }
                @Override public UploadTarget createUpload(String assetKey, String contentType, long maxBytes) {
                    if (outage) { OUTAGE_HITS.incrementAndGet(); throw new MediaStorageFailure("S3Exception"); }
                    return new UploadTarget("PUT", "https://storage.example.test/upload/" + assetKey,
                            Map.of("Content-Type", contentType), Instant.now().plusSeconds(300));
                }
                @Override public Optional<StoredObject> inspect(String assetKey) {
                    if (outage) { OUTAGE_HITS.incrementAndGet(); throw new MediaStorageFailure("S3Exception"); }
                    return Optional.ofNullable(OBJECTS.get(assetKey));
                }
            };
        }
    }

    static final String SKU = "TZP-MMI-1";
    static final String W = "cms-test-token";
    static final String SET = "/api/v1/admin/media/product/" + SKU;
    @Autowired MeterRegistry meters;
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
        m.put("internalKey", "mmi|1");
        m.put("brandCode", "BR-MMI");
        m.put("title", "Media " + SKU);
        m.put("verticalId", "TZV-000001");
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products", m, W, JsonNode.class).getStatusCode().value()).isEqualTo(201);
    }

    double count(String name, String outcome) {
        var c = meters.find(name).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    ResponseEntity<JsonNode> put(Object body) {
        return rest.exchange(url(SET), HttpMethod.PUT, new HttpEntity<>(body, headers(W)), JsonNode.class);
    }

    ResponseEntity<JsonNode> presign(String type, long size, String owner) {
        return post("/api/v1/admin/media/uploads", Map.of("ownerType", "product", "ownerId", owner, "contentType", type, "sizeBytes", size), W, JsonNode.class);
    }

    String upload(String type) {
        return presign(type, 10, SKU).getBody().get("assetKey").asText();
    }

    static Map<String, Object> asset(String key, String type) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("assetId", "a1");
        a.put("assetKey", key);
        a.put("role", "PRIMARY");
        a.put("sortOrder", 0);
        a.put("contentType", type);
        return a;
    }

    static Map<String, Object> set(Long expected, Map<String, Object>... assets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assets", List.of(assets));
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    @Test
    void presign_outcomes_are_counted_by_closed_outcome() {
        double ok = count(MediaMetrics.PRESIGN, "ok"), rejected = count(MediaMetrics.PRESIGN, "rejected"),
                notFound = count(MediaMetrics.PRESIGN, "owner_not_found"), storageErr = count(MediaMetrics.PRESIGN, "storage_error");
        assertThat(presign("image/jpeg", 10, SKU).getStatusCode().value()).isEqualTo(201);
        assertThat(presign("image/gif", 10, SKU).getStatusCode().value()).isEqualTo(422);
        assertThat(presign("image/png", 10, "TZP-NONE").getStatusCode().value()).isEqualTo(404);
        int hits = OUTAGE_HITS.get();
        outage = true;
        try {
            assertThat(presign("image/png", 10, SKU).getStatusCode().value()).isEqualTo(503);
        } finally {
            outage = false;
        }
        assertThat(count(MediaMetrics.PRESIGN, "ok") - ok).isEqualTo(1);
        assertThat(count(MediaMetrics.PRESIGN, "rejected") - rejected).isEqualTo(1);
        assertThat(count(MediaMetrics.PRESIGN, "owner_not_found") - notFound).isEqualTo(1);
        assertThat(OUTAGE_HITS.get() - hits).isGreaterThanOrEqualTo(1);
        assertThat(count(MediaMetrics.PRESIGN, "storage_error") - storageErr).as("one per failure the server saw").isEqualTo(OUTAGE_HITS.get() - hits);
    }

    @Test
    @SuppressWarnings("unchecked")
    void verify_and_write_outcomes_are_counted_through_the_real_put_flow() {
        double missing = count(MediaMetrics.VERIFY, "missing_object"), size = count(MediaMetrics.VERIFY, "size_mismatch"),
                sniff = count(MediaMetrics.VERIFY, "sniff_reject"), type = count(MediaMetrics.VERIFY, "type_mismatch"),
                notIssued = count(MediaMetrics.VERIFY, "key_not_issued"), okV = count(MediaMetrics.VERIFY, "ok"),
                storageErr = count(MediaMetrics.VERIFY, "storage_error"),
                success = count(MediaMetrics.WRITE, "success"), conflict = count(MediaMetrics.WRITE, "conflict"),
                invalid = count(MediaMetrics.WRITE, "validation_failure");

        String never = upload("image/jpeg");
        assertThat(put(set(null, asset(never, "image/jpeg"))).getStatusCode().value()).isEqualTo(422);          // never uploaded
        String png = upload("image/png");
        OBJECTS.put(png, new StoredObject(10, "image/png", PNG));
        assertThat(put(set(null, asset(png, "image/jpeg"))).getStatusCode().value()).isEqualTo(422);            // declared jpeg, bytes png
        String html = upload("image/jpeg");
        OBJECTS.put(html, new StoredObject(10, "image/jpeg", "<html><script>x</script>".getBytes()));
        assertThat(put(set(null, asset(html, "image/jpeg"))).getStatusCode().value()).isEqualTo(422);           // not an image
        String big = upload("image/jpeg");
        OBJECTS.put(big, new StoredObject(99_000_000, "image/jpeg", JPEG));
        assertThat(put(set(null, asset(big, "image/jpeg"))).getStatusCode().value()).isEqualTo(422);            // over the ceiling
        assertThat(put(set(null, asset("p/product/SOMEONE-ELSE/x.jpg", "image/jpeg"))).getStatusCode().value()).isEqualTo(422);   // not issued for this owner
        String ok = upload("image/jpeg");
        OBJECTS.put(ok, new StoredObject(10, "image/jpeg", JPEG));
        int hits = OUTAGE_HITS.get();
        outage = true;
        try {
            assertThat(put(set(null, asset(ok, "image/jpeg"))).getStatusCode().value()).isEqualTo(503);
        } finally {
            outage = false;
        }
        assertThat(put(set(null, asset(ok, "image/jpeg"))).getStatusCode().value()).isEqualTo(201);             // success
        assertThat(put(set(null, asset(ok, "image/jpeg"))).getStatusCode().value()).isEqualTo(409);             // create is not an update: conflict
        Map<String, Object> twoPrimaries = set(1L, asset(ok, "image/jpeg"), asset(ok, "image/jpeg"));
        assertThat(put(twoPrimaries).getStatusCode().value()).isEqualTo(422);                                    // domain invariant: validation failure

        assertThat(count(MediaMetrics.VERIFY, "missing_object") - missing).isEqualTo(1);
        assertThat(count(MediaMetrics.VERIFY, "type_mismatch") - type).isEqualTo(1);
        assertThat(count(MediaMetrics.VERIFY, "sniff_reject") - sniff).isEqualTo(1);
        assertThat(count(MediaMetrics.VERIFY, "size_mismatch") - size).isEqualTo(1);
        assertThat(count(MediaMetrics.VERIFY, "key_not_issued") - notIssued).isEqualTo(1);
        assertThat(count(MediaMetrics.VERIFY, "storage_error") - storageErr).as("one per failure the server saw").isEqualTo(OUTAGE_HITS.get() - hits);
        assertThat(OUTAGE_HITS.get() - hits).isGreaterThanOrEqualTo(1);
        assertThat(count(MediaMetrics.VERIFY, "ok") - okV).as("only the success put verified: a key already in the set is not re-verified").isEqualTo(1);
        assertThat(count(MediaMetrics.WRITE, "success") - success).isEqualTo(1);
        assertThat(count(MediaMetrics.WRITE, "conflict") - conflict).isEqualTo(1);
        assertThat(count(MediaMetrics.WRITE, "validation_failure") - invalid).isEqualTo(1);

        List<String> violations = new ArrayList<>();
        for (Meter m : meters.getMeters()) {
            if (!m.getId().getName().startsWith("media_")) continue;
            for (Tag t : m.getId().getTags()) {
                if (!MediaMetrics.ALLOWED_TAG_KEYS.contains(t.getKey()) || !t.getValue().matches("[a-z_]{2,20}")) {
                    violations.add(m.getId().getName() + " " + t.getKey() + "=" + t.getValue());
                }
            }
        }
        assertThat(violations).as("no owner id, asset key or content type ever becomes a tag").isEmpty();
    }
}
