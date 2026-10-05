package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.media.MediaStorage;
import com.tazzzo.media.StoredObject;
import com.tazzzo.media.UploadTarget;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

/** PR-J: media admin over real HTTP with an in-memory storage double (no real provider ships). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, MediaAdminIT.FakeStorage.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaAdminIT extends AbstractApiIT {

    static final Map<String, StoredObject> OBJECTS = new ConcurrentHashMap<>();
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @TestConfiguration
    static class FakeStorage {
        @Bean
        @Primary
        MediaStorage fakeStorage() {
            return new MediaStorage() {
                @Override public boolean enabled() { return true; }
                @Override public UploadTarget createUpload(String assetKey, String contentType, long maxBytes) {
                    return new UploadTarget("PUT", "https://storage.example.test/upload/" + assetKey,
                            Map.of("Content-Type", contentType), Instant.now().plusSeconds(300));
                }
                @Override public Optional<StoredObject> inspect(String assetKey) { return Optional.ofNullable(OBJECTS.get(assetKey)); }
            };
        }
    }

    static final String SKU = "TZP-MED-1";
    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String SET = "/api/v1/admin/media/product/" + SKU;
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
        m.put("internalKey", "med|1");
        m.put("brandCode", "BR-MED");
        m.put("title", "Media " + SKU);
        m.put("verticalId", "TZV-000001");
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        assertThat(post("/api/v1/products", m, W, JsonNode.class).getStatusCode().value()).isEqualTo(201);
    }

    private ResponseEntity<JsonNode> put(String path, Object body, String token) {
        return rest.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private void assertCode(ResponseEntity<JsonNode> res, int status, String code) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(status);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo(code);
    }

    private static Map<String, Object> asset(String id, String key, String role, int order, String type) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("assetId", id);
        a.put("assetKey", key);
        a.put("role", role);
        a.put("sortOrder", order);
        a.put("contentType", type);
        return a;
    }

    private static Map<String, Object> set(Long expected, Map<String, Object>... assets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assets", List.of(assets));
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    private String upload(String type, long size) {
        ResponseEntity<JsonNode> res = post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", type, "sizeBytes", size), W, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return res.getBody().get("assetKey").asText();
    }

    @Test @Order(1)
    void an_upload_target_is_issued_for_a_server_generated_key_only() {
        ResponseEntity<JsonNode> res = post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/jpeg", "sizeBytes", 1234), W, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        JsonNode b = res.getBody();
        assertThat(b.get("assetKey").asText()).matches("p/product/TZP-MED-1/[0-9a-f-]{36}\\.jpg");
        assertThat(b.get("method").asText()).isEqualTo("PUT");
        assertThat(b.get("url").asText()).endsWith(b.get("assetKey").asText());
        assertThat(b.get("maxBytes").asLong()).isEqualTo(5L * 1024 * 1024);
        assertThat(b.get("expiresAt").asText()).isNotBlank();
        assertThat(upload("image/png", 10)).isNotEqualTo(upload("image/png", 10));
    }

    @Test @Order(2)
    void an_upload_request_is_validated_authorised_and_owner_checked() {
        String u = "/api/v1/admin/media/uploads";
        assertCode(post(u, Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/gif", "sizeBytes", 10), W, JsonNode.class), 422, "INVALID_MEDIA");
        assertCode(post(u, Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 0), W, JsonNode.class), 422, "INVALID_MEDIA");
        assertCode(post(u, Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 6_000_000), W, JsonNode.class), 422, "INVALID_MEDIA");
        assertCode(post(u, Map.of("ownerType", "banner", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 10), W, JsonNode.class), 422, "INVALID_MEDIA");
        assertCode(post(u, Map.of("ownerType", "product", "ownerId", "TZP-NONE", "contentType", "image/png", "sizeBytes", 10), W, JsonNode.class), 404, "NOT_FOUND");
        assertCode(post(u, Map.of("ownerType", "product"), W, JsonNode.class), 422, "INVALID_MEDIA");
        assertThat(post(u, Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 10), null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(post(u, Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 10), R, JsonNode.class).getStatusCode().value()).isEqualTo(403);
    }

    @Test @Order(3)
    void a_set_can_only_reference_objects_that_exist_and_really_are_the_declared_image() {
        assertCode(get(SET, R, JsonNode.class), 404, "NOT_FOUND");
        String missing = upload("image/jpeg", 10);
        assertCode(put(SET, set(null, asset("a1", missing, "PRIMARY", 0, "image/jpeg")), W), 422, "INVALID_MEDIA");   // never uploaded

        String png = upload("image/png", 10);
        OBJECTS.put(png, new StoredObject(10, "image/png", PNG));
        assertCode(put(SET, set(null, asset("a1", png, "PRIMARY", 0, "image/jpeg")), W), 422, "INVALID_MEDIA");        // declared jpeg, bytes png

        String html = upload("image/jpeg", 10);
        OBJECTS.put(html, new StoredObject(10, "image/jpeg", "<html><script>x</script>".getBytes()));
        assertCode(put(SET, set(null, asset("a1", html, "PRIMARY", 0, "image/jpeg")), W), 422, "INVALID_MEDIA");       // not an image at all

        String big = upload("image/jpeg", 10);
        OBJECTS.put(big, new StoredObject(99_000_000, "image/jpeg", JPEG));
        assertCode(put(SET, set(null, asset("a1", big, "PRIMARY", 0, "image/jpeg")), W), 422, "INVALID_MEDIA");        // over the ceiling
        assertThat(db.getCollection("media_refs").countDocuments()).as("nothing was written by any refused set").isZero();

        String ok = upload("image/jpeg", 10);
        OBJECTS.put(ok, new StoredObject(10, "image/jpeg", JPEG));
        ResponseEntity<JsonNode> created = put(SET, set(null, asset("a1", ok, "PRIMARY", 0, "image/jpeg")), W);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("version").asLong()).isEqualTo(1);
        assertThat(created.getBody().at("/assets/0/assetKey").asText()).isEqualTo(ok);
        assertThat(get(SET, R, JsonNode.class).getBody().get("assets")).hasSize(1);
    }

    @Test @Order(4)
    void updates_are_cas_set_level_invariants_hold_and_already_referenced_keys_are_not_reverified() {
        String ok = db.getCollection("media_refs").find().first().getList("assets", Document.class).get(0).getString("asset_key");
        String second = upload("image/png", 10);
        OBJECTS.put(second, new StoredObject(10, "image/png", PNG));
        OBJECTS.remove(ok);   // the stored object vanished, but the key is already in the set: only NEW keys are verified
        ResponseEntity<JsonNode> updated = put(SET, set(1L, asset("a1", ok, "PRIMARY", 0, "image/jpeg"), asset("a2", second, "GALLERY", 1, "image/png")), W);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().get("version").asLong()).isEqualTo(2);
        assertCode(put(SET, set(1L, asset("a1", ok, "PRIMARY", 0, "image/jpeg")), W), 409, "STALE_VERSION");
        assertCode(put(SET, set(null, asset("a1", ok, "PRIMARY", 0, "image/jpeg")), W), 409, "STALE_VERSION");        // create is not an update
        assertCode(put(SET, set(2L, asset("a1", ok, "PRIMARY", 0, "image/jpeg"), asset("a2", second, "PRIMARY", 1, "image/png")), W), 422, "INVALID_MEDIA");   // two primaries
        assertCode(put(SET, set(2L, asset("a1", ok, "PRIMARY", 0, "image/jpeg"), asset("a1", second, "GALLERY", 1, "image/png")), W), 422, "INVALID_MEDIA");   // duplicate id
        assertCode(put(SET, set(2L, asset("a1", "../etc/passwd", "PRIMARY", 0, null)), W), 422, "INVALID_MEDIA");
        Map<String, Object> badRole = asset("a1", ok, "BANNER", 0, "image/jpeg");
        assertCode(put(SET, set(2L, badRole), W), 422, "INVALID_MEDIA");
        assertCode(put(SET, Map.of("expectedVersion", 2), W), 422, "INVALID_MEDIA");                                  // assets missing
        ResponseEntity<JsonNode> cleared = put(SET, Map.of("assets", List.of(), "expectedVersion", 2), W);
        assertThat(cleared.getStatusCode().value()).isEqualTo(200);
        assertThat(cleared.getBody().get("assets")).isEmpty();
    }

    @Test @Order(5)
    void auth_unknown_owner_and_attribution() {
        assertThat(put(SET, set(null), null).getStatusCode().value()).isEqualTo(401);
        assertThat(put(SET, set(5L), R).getStatusCode().value()).isEqualTo(403);
        assertCode(put("/api/v1/admin/media/product/TZP-NONE", set(null), W), 404, "NOT_FOUND");
        assertThat(db.getCollection("media_refs").countDocuments(Filters.eq("owner_id", "TZP-NONE"))).as("nothing written for an unknown owner").isZero();
        assertCode(put("/api/v1/admin/media/banner/" + SKU, set(null), W), 422, "INVALID_MEDIA");
        assertCode(get("/api/v1/admin/media/sku/" + SKU, R, JsonNode.class), 404, "NOT_FOUND");

        List<Document> events = db.getCollection("product_events").find(Filters.eq("product_id", SKU)).into(new ArrayList<>())
                .stream().filter(e -> e.toJson().contains("MEDIA_SET_UPDATED")).toList();
        assertThat(events).as("create, update, clear: refused writes left no event").hasSize(3);
        assertThat(events).allSatisfy(e -> {
            assertThat(ActorDocuments.fromEvent(e)).as("attributed").isPresent();
            assertThat(e.toJson()).doesNotContain("cms-test-token");
        });
    }
}
