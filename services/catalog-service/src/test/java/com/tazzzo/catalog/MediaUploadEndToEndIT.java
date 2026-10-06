package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real media flow over HTTP with {@code tazzzo.media.storage.provider=s3} against an S3-compatible store in a
 * container (Adobe S3Mock): the admin asks for an upload target, uploads straight to the store with the returned
 * instruction, references the key, and the backend verifies the stored bytes before the key enters the media set. The
 * rejections are real too: a key that was never uploaded, bytes that do not match the declared type, and the upload
 * policy, ownership and role checks. (S3Mock verifies no signatures; the store-side refusal of a mismatched
 * Content-Type is AWS S3 behaviour pinned by the URL contract in {@code S3MediaStorageIT}.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class MediaUploadEndToEndIT extends AbstractApiIT {

    static final String BUCKET = "tazzzo-media-e2e";
    @SuppressWarnings("resource")
    static final GenericContainer<?> STORE = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.11.0"))
            .withExposedPorts(9090).withEnv("initialBuckets", BUCKET);
    static final String SKU = "TZP-MED-3";
    static final String SET = "/api/v1/admin/media/product/" + SKU;
    static final String W = "cms-test-token";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    static {
        STORE.start();
    }

    static String endpoint() {
        return "http://" + STORE.getHost() + ":" + STORE.getMappedPort(9090);
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry r) {
        r.add("tazzzo.media.storage.provider", () -> "s3");
        r.add("tazzzo.media.storage.s3.bucket", () -> BUCKET);
        r.add("tazzzo.media.storage.s3.region", () -> "us-east-1");
        r.add("tazzzo.media.storage.s3.endpoint", MediaUploadEndToEndIT::endpoint);
        r.add("tazzzo.media.storage.s3.path-style", () -> "true");
        r.add("tazzzo.media.storage.s3.access-key", () -> "e2e-access");
        r.add("tazzzo.media.storage.s3.secret-key", () -> "e2e-secret-fixture");
        r.add("tazzzo.media.storage.s3.presign-ttl-seconds", () -> "120");
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.example.test");
    }

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    final HttpClient http = HttpClient.newHttpClient();

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
        assertThat(post("/api/v1/products", m, W, JsonNode.class).getStatusCode().value()).isEqualTo(201);
    }

    @AfterAll
    static void stop() {
        STORE.stop();
    }

    @Test
    void upload_then_reference_then_read(CapturedOutput log) throws Exception {
        // 1. the admin asks for an upload target: a server-generated key and a presigned PUT
        ResponseEntity<JsonNode> target = post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", PNG.length), W, JsonNode.class);
        assertThat(target.getStatusCode().value()).as(String.valueOf(target.getBody())).isEqualTo(201);
        JsonNode t = target.getBody();
        String key = t.get("assetKey").asText();
        assertThat(key).startsWith("p/product/TZP-MED-3/").endsWith(".png");
        assertThat(t.get("method").asText()).isEqualTo("PUT");
        assertThat(t.get("url").asText()).startsWith(endpoint()).contains(key).doesNotContain("e2e-secret-fixture");
        assertThat(t.get("maxBytes").asLong()).isEqualTo(5L * 1024 * 1024);

        // 2. referencing the key BEFORE uploading is refused: the store holds nothing
        assertCode(put(SET, set(List.of(asset("a1", key, "PRIMARY", 0, "image/png")), null)), 422, "INVALID_MEDIA");

        // 3. the client uploads straight to the store using the instruction as given
        assertThat(upload(t, PNG, "image/png")).isEqualTo(200);

        // 4. declaring a type that does not match the stored bytes is refused
        assertCode(put(SET, set(List.of(asset("a1", key, "PRIMARY", 0, "image/jpeg")), null)), 422, "INVALID_MEDIA");

        // 5. the truthful reference is verified against the stored object and accepted
        ResponseEntity<JsonNode> created = put(SET, set(List.of(asset("a1", key, "PRIMARY", 0, "image/png")), null));
        assertThat(created.getStatusCode().value()).as(String.valueOf(created.getBody())).isEqualTo(201);
        assertThat(created.getBody().get("assets").get(0).get("assetKey").asText()).isEqualTo(key);
        assertThat(created.getBody().get("version").asLong()).isEqualTo(1);

        // 6. a second asset of another type, referenced with its CAS version
        ResponseEntity<JsonNode> second = post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/jpeg", "sizeBytes", JPEG.length), W, JsonNode.class);
        String key2 = second.getBody().get("assetKey").asText();
        assertCode(put(SET, set(List.of(asset("a1", key, "PRIMARY", 0, "image/png"), asset("a2", key2, "GALLERY", 1, "image/jpeg")), 1L)),
                422, "INVALID_MEDIA");   // not uploaded yet
        assertThat(upload(second.getBody(), JPEG, "image/jpeg")).isEqualTo(200);
        ResponseEntity<JsonNode> updated = put(SET, set(List.of(asset("a1", key, "PRIMARY", 0, "image/png"), asset("a2", key2, "GALLERY", 1, "image/jpeg")), 1L));
        assertThat(updated.getStatusCode().value()).as(String.valueOf(updated.getBody())).isEqualTo(200);
        assertThat(updated.getBody().get("assets")).hasSize(2);

        // 7. readable back; nothing secret was logged
        ResponseEntity<JsonNode> read = get(SET, W, JsonNode.class);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getBody().get("assets").get(1).get("assetKey").asText()).isEqualTo(key2);
        // the startup line "media_storage provider=s3 … credentials=static" precedes this test's capture window; what
        // matters here is that nothing in the request path ever logs the credential
        assertThat(log.getOut()).doesNotContain("e2e-secret-fixture");
    }

    @Test
    void upload_policy_and_ownership_still_apply() {
        assertCode(post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/gif", "sizeBytes", 10), W, JsonNode.class),
                422, "INVALID_MEDIA");
        assertCode(post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", "TZP-NOPE", "contentType", "image/png", "sizeBytes", 10), W, JsonNode.class),
                404, "NOT_FOUND");
        assertThat(post("/api/v1/admin/media/uploads",
                Map.of("ownerType", "product", "ownerId", SKU, "contentType", "image/png", "sizeBytes", 10), "read-test-token",
                JsonNode.class).getStatusCode().value()).isEqualTo(403);
    }

    // ---------- helpers ----------

    private int upload(JsonNode target, byte[] bytes, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target.get("url").asText()))
                .method(target.get("method").asText(), HttpRequest.BodyPublishers.ofByteArray(bytes));
        target.get("headers").fields().forEachRemaining(h -> {
            if (!h.getKey().equalsIgnoreCase("Content-Type")) b.header(h.getKey(), h.getValue().asText());
        });
        b.header("Content-Type", contentType);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode();
    }

    private ResponseEntity<JsonNode> put(String path, Object body) {
        return rest.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers(W)), JsonNode.class);
    }

    private static Map<String, Object> asset(String id, String key, String role, int order, String contentType) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("assetId", id);
        a.put("assetKey", key);
        a.put("role", role);
        a.put("sortOrder", order);
        a.put("altText", "alt " + id);
        a.put("contentType", contentType);
        return a;
    }

    private static Map<String, Object> set(List<Map<String, Object>> assets, Long expected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assets", assets);
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    private static void assertCode(ResponseEntity<JsonNode> r, int status, String code) {
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(status);
        assertThat(r.getBody().at("/error/code").asText()).isEqualTo(code);
    }
}
