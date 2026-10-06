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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-Q: CMS blocks and app config over real HTTP, Mongo and Redis: admin writes, public reads, windows, CAS, audit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentIT extends AbstractConsumerIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String BLOCKS = "/api/v1/admin/content/blocks";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_content_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> W);
        r.add("tazzzo.auth.read-token", () -> R);
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString("content-cursor-fixture-key-32b!!!".getBytes(StandardCharsets.UTF_8)));
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.tazzzo.com");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @BeforeEach
    void clean() {
        schemaBootstrap.bootstrap(db);
        for (String c : List.of("content_blocks", "domain_events")) db.getCollection(c).deleteMany(new Document());
        db.getCollection("system_config").deleteMany(new Document("_id", "app_config"));
    }

    private ResponseEntity<JsonNode> send(HttpMethod m, String path, String token, Object body) {
        HttpHeaders h = bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private static Map<String, Object> block(String type, String title, int sort, Map<String, Object> payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("sort", sort);
        m.put("payload", payload);
        return m;
    }

    private JsonNode create(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, BLOCKS, W, body);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        return r.getBody();
    }

    private JsonNode publish(JsonNode b) {
        return send(HttpMethod.POST, BLOCKS + "/" + b.get("blockId").asText() + "/status", W,
                Map.of("to", "PUBLISHED", "expectedVersion", b.get("version").asLong())).getBody();
    }

    private List<String> homeIds() {
        JsonNode home = get("/v1/content/home", JsonNode.class).getBody();
        List<String> ids = new ArrayList<>();
        home.get("blocks").forEach(b -> ids.add(b.get("blockId").asText()));
        return ids;
    }

    @Test
    void only_published_blocks_inside_their_window_are_live_in_display_order() {
        JsonNode rail = create(block("PRODUCT_RAIL", "Top picks", 2, Map.of("ids", List.of("TZP-1", "TZP-2"))));
        JsonNode banner = create(block("BANNER", "Diwali", 1, Map.of("imageAssetKey", "cms/home/diwali.webp", "link", "category:TZC-000001")));
        JsonNode draft = create(block("CATEGORY_GRID", "Shop by aisle", 0, Map.of("ids", List.of("TZC-000001"))));
        Map<String, Object> future = block("PRODUCT_RAIL", "Tomorrow", 0, Map.of("ids", List.of("TZP-3")));
        future.put("startsAt", Instant.now().plusSeconds(3600).toString());
        JsonNode later = create(future);
        Map<String, Object> past = block("PRODUCT_RAIL", "Yesterday", 0, Map.of("ids", List.of("TZP-4")));
        past.put("startsAt", Instant.now().minusSeconds(7200).toString());
        past.put("endsAt", Instant.now().minusSeconds(3600).toString());
        JsonNode expired = create(past);
        for (JsonNode b : List.of(rail, banner, later, expired)) publish(b);

        assertThat(homeIds()).containsExactly(banner.get("blockId").asText(), rail.get("blockId").asText());
        assertThat(homeIds()).doesNotContain(draft.get("blockId").asText(), later.get("blockId").asText(), expired.get("blockId").asText());
        ResponseEntity<JsonNode> res = get("/v1/content/home", JsonNode.class);
        assertThat(res.getHeaders().getCacheControl()).isEqualTo("public, max-age=60");
        JsonNode first = res.getBody().get("blocks").get(0);
        assertThat(first.get("imageUrl").asText()).isEqualTo("https://cdn.tazzzo.com/cms/home/diwali.webp");
        assertThat(first.get("link").asText()).isEqualTo("category:TZC-000001");
        assertThat(res.getBody().toString()).doesNotContain("version").doesNotContain("DRAFT").doesNotContain("status");

        JsonNode unpublished = send(HttpMethod.POST, BLOCKS + "/" + banner.get("blockId").asText() + "/status", W,
                Map.of("to", "DRAFT", "expectedVersion", 2)).getBody();
        assertThat(unpublished.get("status").asText()).isEqualTo("DRAFT");
        assertThat(homeIds()).containsExactly(rail.get("blockId").asText());
    }

    @Test
    void admin_writes_are_validated_cas_guarded_audited_and_archive_is_final() {
        JsonNode b = create(block("PRODUCT_RAIL", "Top picks", 2, Map.of("ids", List.of("TZP-1"))));
        String id = b.get("blockId").asText();
        Map<String, Object> upd = block(null, "Best sellers", 3, Map.of("ids", List.of("TZP-1", "TZP-9")));
        upd.remove("type");
        upd.put("expectedVersion", 1);
        JsonNode updated = send(HttpMethod.PUT, BLOCKS + "/" + id, W, upd).getBody();
        assertThat(updated.get("title").asText()).isEqualTo("Best sellers");
        assertThat(updated.get("version").asLong()).isEqualTo(2);
        assertThat(send(HttpMethod.PUT, BLOCKS + "/" + id, W, upd).getBody().at("/error/code").asText()).as("stale").isEqualTo("STALE_VERSION");
        Map<String, Object> retype = new LinkedHashMap<>(upd);
        retype.put("type", "BANNER");
        retype.put("expectedVersion", 2);
        assertThat(send(HttpMethod.PUT, BLOCKS + "/" + id, W, retype).getStatusCode().value()).as("type is fixed").isEqualTo(422);
        assertThat(send(HttpMethod.POST, BLOCKS + "/" + id + "/status", W, Map.of("to", "ARCHIVED", "expectedVersion", 2)).getBody()
                .get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(send(HttpMethod.POST, BLOCKS + "/" + id + "/status", W, Map.of("to", "PUBLISHED", "expectedVersion", 3)).getStatusCode().value())
                .as("archived is final").isEqualTo(409);
        upd.put("expectedVersion", 3);
        assertThat(send(HttpMethod.PUT, BLOCKS + "/" + id, W, upd).getStatusCode().value()).isEqualTo(409);

        List<Document> events = db.getCollection("domain_events").find(new Document("aggregate_id", id)).into(new ArrayList<>());
        assertThat(events).extracting(e -> e.getString("type")).containsExactly("CONTENT_BLOCK_CREATED", "CONTENT_BLOCK_UPDATED", "CONTENT_BLOCK_ARCHIVED");
        assertThat(events).allSatisfy(e -> assertThat(e.get("actor", Document.class).getString("type")).isEqualTo("SERVICE_ACCOUNT"));
    }

    @Test
    void invalid_content_and_auth_are_refused_and_write_nothing() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                block("BANNER", "B", 1, Map.of("imageAssetKey", "cms/x.webp", "link", "https://evil.example")),
                block("BANNER", "B", 1, Map.of("imageAssetKey", "../secret", "link", "search:rice")),
                block("PRODUCT_RAIL", "R", 1, Map.of("ids", List.of())),
                block("WIDGET", "W", 1, Map.of("ids", List.of("TZP-1"))),
                block("PRODUCT_RAIL", "", 1, Map.of("ids", List.of("TZP-1"))))) {
            assertThat(send(HttpMethod.POST, BLOCKS, W, bad).getStatusCode().value()).as(bad.toString()).isEqualTo(422);
        }
        Map<String, Object> badTime = block("PRODUCT_RAIL", "R", 1, Map.of("ids", List.of("TZP-1")));
        badTime.put("startsAt", "tomorrow");
        assertThat(send(HttpMethod.POST, BLOCKS, W, badTime).getStatusCode().value()).isEqualTo(422);
        assertThat(send(HttpMethod.POST, BLOCKS, R, block("PRODUCT_RAIL", "R", 1, Map.of("ids", List.of("TZP-1")))).getStatusCode().value()).isEqualTo(403);
        assertThat(send(HttpMethod.POST, BLOCKS, null, block("PRODUCT_RAIL", "R", 1, Map.of("ids", List.of("TZP-1")))).getStatusCode().value()).isEqualTo(401);
        assertThat(db.getCollection("content_blocks").countDocuments()).isZero();
        assertThat(db.getCollection("domain_events").countDocuments()).isZero();
        assertThat(send(HttpMethod.GET, BLOCKS + "/CB_doesnotexistdoesnotexist", R, null).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/v1/content/home?x=1", JsonNode.class).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void app_config_defaults_then_cas_writes_and_the_public_view() {
        JsonNode def = get("/v1/app-config", JsonNode.class).getBody();
        assertThat(def.get("storeOpen").asBoolean()).isTrue();
        assertThat(def.get("maintenance").get("enabled").asBoolean()).isFalse();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("storeOpen", false);
        cfg.put("maintenance", true);
        cfg.put("maintenanceMessage", "Back at 6 pm");
        cfg.put("minAndroid", "1.2.0");
        cfg.put("latestAndroid", "1.4.0");
        cfg.put("supportEmail", "help@tazzzo.com");
        cfg.put("expectedVersion", 0);
        ResponseEntity<JsonNode> put = send(HttpMethod.PUT, "/api/v1/admin/app-config", W, cfg);
        assertThat(put.getStatusCode().value()).as(String.valueOf(put.getBody())).isEqualTo(200);
        assertThat(put.getBody().get("version").asLong()).isEqualTo(1);
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/app-config", W, cfg).getStatusCode().value()).as("create twice").isEqualTo(409);
        cfg.put("expectedVersion", 7);
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/app-config", W, cfg).getStatusCode().value()).as("stale").isEqualTo(409);

        JsonNode pub = get("/v1/app-config", JsonNode.class).getBody();
        assertThat(pub.get("storeOpen").asBoolean()).isFalse();
        assertThat(pub.get("maintenance").get("message").asText()).isEqualTo("Back at 6 pm");
        assertThat(pub.get("android").get("minSupported").asText()).isEqualTo("1.2.0");
        assertThat(pub.get("support").get("email").asText()).isEqualTo("help@tazzzo.com");
        assertThat(pub.toString()).doesNotContain("version\"");

        cfg.put("expectedVersion", 1);
        cfg.put("minAndroid", "2.0");
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/app-config", W, cfg).getStatusCode().value()).as("min above latest").isEqualTo(422);
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/app-config", R, cfg).getStatusCode().value()).isEqualTo(403);

        // maintenance switched off but the message kept for next time: the public view must not show a stale banner
        cfg.put("minAndroid", "1.2.0");
        cfg.put("maintenance", false);
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/app-config", W, cfg).getStatusCode().value()).isEqualTo(200);
        JsonNode off = get("/v1/app-config", JsonNode.class).getBody();
        assertThat(off.get("maintenance").get("enabled").asBoolean()).isFalse();
        assertThat(off.get("maintenance").path("message").isMissingNode() || off.get("maintenance").get("message").isNull())
                .as(off.toString()).isTrue();
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_type", "app_config"))).isEqualTo(2);
    }

    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry registry;

    long charged(com.tazzzo.catalog.consumer.ConsumerObservability.Route route) {
        var summary = registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.RATE_LIMIT_COST)
                .tag("route", route.tag()).summary();
        return summary == null ? 0 : summary.count();
    }

    @Test
    void both_public_reads_are_admission_charged_on_their_own_route() {
        var home = com.tazzzo.catalog.consumer.ConsumerObservability.Route.CONTENT_HOME;
        var config = com.tazzzo.catalog.consumer.ConsumerObservability.Route.APP_CONFIG;
        long h0 = charged(home), c0 = charged(config);
        send(HttpMethod.GET, "/v1/content/home", null, null);
        send(HttpMethod.GET, "/v1/content/home", null, null);
        send(HttpMethod.GET, "/v1/app-config", null, null);
        assertThat(charged(home) - h0).isEqualTo(2);
        assertThat(charged(config) - c0).isEqualTo(1);
    }
}
