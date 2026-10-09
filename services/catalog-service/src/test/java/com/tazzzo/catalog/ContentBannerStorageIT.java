package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Banner images end to end against a SigV4- and precondition-enforcing S3 store (Versity S3 Gateway): the CMS asks for a
 * content upload target, PUTs the bytes with exactly the returned headers, references the key from a BANNER, and the
 * published banner reaches the public Home with its CDN URL. Refused: a key never uploaded, a product (p/) key, bytes
 * that are not an image, and a second PUT on the same write-once URL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentBannerStorageIT extends AbstractConsumerIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String ACCESS = "banner-it-access";
    static final String SECRET = "banner-it-secret-fixture";
    static final String BUCKET = "tazzzo-media-banner-it";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @SuppressWarnings("resource")
    static final GenericContainer<?> STORE = new GenericContainer<>(DockerImageName.parse(
            "versity/versitygw@sha256:30292fc2eeacc67a36993b01f7a7a5e3361a19cced0e80c1d71cfa2a4b0a2499"))
            .withExposedPorts(7070).withEnv("ROOT_ACCESS_KEY", ACCESS).withEnv("ROOT_SECRET_KEY", SECRET)
            .withCommand("--port", ":7070", "posix", "/tmp")
            .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(2));

    static {
        STORE.start();
        try (S3Client s3 = S3Client.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
                .endpointOverride(URI.create(endpoint())).httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
    }

    static String endpoint() {
        return "http://" + STORE.getHost() + ":" + STORE.getMappedPort(7070);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_content_banner_storage_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> W);
        r.add("tazzzo.auth.read-token", () -> R);
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString("banner-store-fixture-key-32b!!!!".getBytes(StandardCharsets.UTF_8)));
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.example.test");
        r.add("tazzzo.media.storage.provider", () -> "s3");
        r.add("tazzzo.media.storage.s3.bucket", () -> BUCKET);
        r.add("tazzzo.media.storage.s3.region", () -> "us-east-1");
        r.add("tazzzo.media.storage.s3.endpoint", ContentBannerStorageIT::endpoint);
        r.add("tazzzo.media.storage.s3.path-style", () -> "true");
        r.add("tazzzo.media.storage.s3.access-key", () -> ACCESS);
        r.add("tazzzo.media.storage.s3.secret-key", () -> SECRET);
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        schemaBootstrap.bootstrap(db);
        for (String c : List.of("content_blocks", "domain_events")) db.getCollection(c).deleteMany(new Document());
    }

    @Test
    void a_banner_image_uploaded_through_the_content_target_is_verified_and_reaches_the_public_home() throws Exception {
        JsonNode target = uploadTarget("image/png", PNG.length);
        String key = target.get("assetKey").asText();
        assertThat(key).matches("c/home/[0-9a-f-]{36}\\.png");
        assertThat(target.at("/headers/If-None-Match").asText()).isEqualTo("*");
        assertThat(put(target, PNG)).as("the signed upload").isEqualTo(200);
        assertThat(put(target, PNG)).as("write-once: the URL cannot replace verified bytes").isEqualTo(412);

        JsonNode created = create(banner("Mango season", key));
        assertThat(created.get("imageUrl").asText()).isEqualTo("https://cdn.example.test/" + key);
        publish(created);
        JsonNode home = get("/v1/content/home?channel=web", JsonNode.class).getBody();
        assertThat(home.at("/blocks/0/imageUrl").asText()).isEqualTo("https://cdn.example.test/" + key);
    }

    @Test
    void keys_that_were_not_uploaded_for_content_or_do_not_hold_an_image_are_refused() throws Exception {
        assertThat(createStatus(banner("Never uploaded", "c/home/00000000-0000-0000-0000-000000000000.png"))).isEqualTo(422);
        assertThat(createStatus(banner("Product key", "p/product/TZP-1/x.png"))).as("not issued for content").isEqualTo(422);

        byte[] notImage = "hello, not a png".getBytes(StandardCharsets.UTF_8);
        JsonNode t = uploadTarget("image/png", notImage.length);
        assertThat(put(t, notImage)).as("the store accepts the declared size and type").isEqualTo(200);
        assertThat(createStatus(banner("Text bytes", t.get("assetKey").asText()))).as("magic bytes say no").isEqualTo(422);
        assertThat(db.getCollection("content_blocks").countDocuments()).isZero();
    }

    // ---------- helpers ----------

    private JsonNode uploadTarget(String type, long size) {
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, "/api/v1/admin/content/uploads", Map.of("contentType", type, "sizeBytes", size));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        return r.getBody();
    }

    private int put(JsonNode target, byte[] bytes) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target.get("url").asText())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        target.get("headers").fields().forEachRemaining(h -> {
            if (!h.getKey().equalsIgnoreCase("Content-Length")) b.header(h.getKey(), h.getValue().asText());
        });
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static Map<String, Object> banner(String title, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "BANNER");
        m.put("title", title);
        m.put("sort", 1);
        m.put("payload", Map.of("imageAssetKey", key, "link", "search:mango"));
        m.put("audience", "BOTH");
        return m;
    }

    private ResponseEntity<JsonNode> send(HttpMethod m, String path, Object body) {
        HttpHeaders h = bearer(W);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private int createStatus(Map<String, Object> body) {
        return send(HttpMethod.POST, "/api/v1/admin/content/blocks", body).getStatusCode().value();
    }

    private JsonNode create(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, "/api/v1/admin/content/blocks", body);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        return r.getBody();
    }

    private void publish(JsonNode b) {
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, "/api/v1/admin/content/blocks/" + b.get("blockId").asText() + "/status",
                Map.of("to", "PUBLISHED", "expectedVersion", b.get("version").asLong()));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
    }
}
