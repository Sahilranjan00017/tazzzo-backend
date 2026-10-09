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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The banner model over real HTTP: subtitle, alt text and a desktop image round-trip and reach the public Home; authorship
 * and the derived schedule state are visible to editors; a reorder is all-or-nothing and changes the order every channel
 * renders; the admin preview shows drafts and future content for one channel without ever exposing them publicly; a reader
 * can preview but not mutate; banner uploads are refused while no storage is configured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentBannerModelIT extends AbstractConsumerIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String BLOCKS = "/api/v1/admin/content/blocks";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_content_banner_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> W);
        r.add("tazzzo.auth.read-token", () -> R);
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString("banner-cursor-fixture-key-32b!!!".getBytes(StandardCharsets.UTF_8)));
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
    }

    @Test
    void banner_presentation_fields_round_trip_and_reach_the_public_home() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("imageAssetKey", "c/home/mobile.webp");
        payload.put("desktopImageAssetKey", "c/home/desktop.webp");
        payload.put("link", "category:TZC-000001");
        payload.put("subtitle", "Fresh from the farm, every morning");
        payload.put("altText", "A basket of mangoes on a wooden table");
        JsonNode created = create(block("BANNER", "Mango season", 1, payload, "BOTH", null, null));
        assertThat(created.at("/payload/subtitle").asText()).isEqualTo("Fresh from the farm, every morning");
        assertThat(created.at("/payload/desktopImageAssetKey").asText()).isEqualTo("c/home/desktop.webp");
        assertThat(created.get("imageUrl").asText()).as("the CMS sees what clients load").isEqualTo("https://cdn.tazzzo.com/c/home/mobile.webp");
        assertThat(created.get("desktopImageUrl").asText()).isEqualTo("https://cdn.tazzzo.com/c/home/desktop.webp");
        assertThat(created.get("createdBy").asText()).isNotBlank();
        assertThat(created.get("updatedBy").asText()).isEqualTo(created.get("createdBy").asText());
        assertThat(created.get("effectiveStatus").asText()).isEqualTo("DRAFT");
        String plain = publish(create(block("BANNER", "No extras", 2, Map.of("imageAssetKey", "c/home/plain.webp", "link", "search:rice"), "BOTH", null, null)));
        publish(created);

        for (String channel : List.of("?channel=app", "?channel=web", "")) {
            JsonNode blocks = home(channel);
            assertThat(blocks).hasSize(2);
            JsonNode b = blocks.get(0);
            assertThat(b.get("subtitle").asText()).isEqualTo("Fresh from the farm, every morning");
            assertThat(b.get("altText").asText()).isEqualTo("A basket of mangoes on a wooden table");
            assertThat(b.get("imageUrl").asText()).isEqualTo("https://cdn.tazzzo.com/c/home/mobile.webp");
            assertThat(b.get("desktopImageUrl").asText()).isEqualTo("https://cdn.tazzzo.com/c/home/desktop.webp");
            JsonNode p = blocks.get(1);
            assertThat(p.get("blockId").asText()).isEqualTo(plain);
            assertThat(p.get("altText").asText()).as("alt falls back to the title").isEqualTo("No extras");
            assertThat(p.has("subtitle")).isFalse();
            assertThat(p.has("desktopImageUrl")).as("absent = use imageUrl").isFalse();
        }
    }

    @Test
    void banner_only_fields_are_validated_and_refused_elsewhere() {
        assertThat(status(HttpMethod.POST, BLOCKS, W, block("PRODUCT_RAIL", "Rail", 1,
                Map.of("ids", List.of("TZP-1"), "subtitle", "nope"), "BOTH", null, null))).isEqualTo(422);
        assertThat(status(HttpMethod.POST, BLOCKS, W, block("BANNER", "Alt markup", 1,
                Map.of("imageAssetKey", "c/home/a.webp", "link", "search:rice", "altText", "<img onerror=x>"), "BOTH", null, null))).isEqualTo(422);
        assertThat(status(HttpMethod.POST, BLOCKS, W, block("BANNER", "Traversal", 1,
                Map.of("imageAssetKey", "c/home/a.webp", "link", "search:rice", "desktopImageAssetKey", "../secret.webp"), "BOTH", null, null))).isEqualTo(422);
        assertThat(status(HttpMethod.POST, BLOCKS, W, block("BANNER", "Url link", 1,
                Map.of("imageAssetKey", "c/home/a.webp", "link", "https://evil.example"), "BOTH", null, null))).isEqualTo(422);
        assertThat(status(HttpMethod.POST, BLOCKS, W, block("BANNER", "Long subtitle", 1,
                Map.of("imageAssetKey", "c/home/a.webp", "link", "search:rice", "subtitle", "x".repeat(121)), "BOTH", null, null))).isEqualTo(422);
        assertThat(db.getCollection("content_blocks").countDocuments()).isZero();
    }

    @Test
    void the_effective_status_tells_scheduled_live_and_expired_apart() {
        Instant now = Instant.now();
        String future = publish(create(block("PRODUCT_RAIL", "Future", 1, Map.of("ids", List.of("TZP-1")), "BOTH",
                now.plus(2, ChronoUnit.DAYS).toString(), null)));
        String live = publish(create(block("PRODUCT_RAIL", "Live", 2, Map.of("ids", List.of("TZP-2")), "BOTH",
                now.minus(1, ChronoUnit.DAYS).toString(), now.plus(1, ChronoUnit.DAYS).toString())));
        String expired = publish(create(block("PRODUCT_RAIL", "Expired", 3, Map.of("ids", List.of("TZP-3")), "BOTH",
                now.minus(2, ChronoUnit.DAYS).toString(), now.minus(1, ChronoUnit.DAYS).toString())));
        Map<String, String> state = new LinkedHashMap<>();
        send(HttpMethod.GET, BLOCKS, R, null).getBody().get("items").forEach(b -> state.put(b.get("blockId").asText(), b.get("effectiveStatus").asText()));
        assertThat(state).containsEntry(future, "SCHEDULED").containsEntry(live, "LIVE").containsEntry(expired, "EXPIRED");
        assertThat(ids(home("?channel=app"))).as("TEST 11: absent before start; expired gone").containsExactly(live);
    }

    @Test
    void reorder_is_all_or_nothing_and_every_channel_renders_the_new_order() {
        JsonNode a = create(block("PRODUCT_RAIL", "A", 1, Map.of("ids", List.of("TZP-1")), "BOTH", null, null));
        JsonNode b = create(block("PRODUCT_RAIL", "B", 2, Map.of("ids", List.of("TZP-2")), "APP_ONLY", null, null));
        JsonNode c = create(block("PRODUCT_RAIL", "C", 3, Map.of("ids", List.of("TZP-3")), "WEB_ONLY", null, null));
        String ia = publish(a), ib = publish(b), ic = publish(c);
        assertThat(ids(home("?channel=app"))).containsExactly(ia, ib);
        assertThat(ids(home("?channel=web"))).containsExactly(ia, ic);
        long va = version(ia), vb = version(ib), vc = version(ic);

        // a stale version anywhere refuses the whole reorder
        ResponseEntity<JsonNode> stale = send(HttpMethod.POST, BLOCKS + "/reorder", W, reorder(ic, vc, ib, vb, ia, va - 1));
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(List.of(version(ia), version(ib), version(ic))).as("all or nothing: no block was written").containsExactly(va, vb, vc);
        assertThat(ids(home("?channel=app"))).as("nothing moved").containsExactly(ia, ib);
        // a list that misses a block (another editor's view) is refused
        assertThat(send(HttpMethod.POST, BLOCKS + "/reorder", W, Map.of("placement", "HOME",
                "order", List.of(Map.of("blockId", ic, "expectedVersion", vc)))).getStatusCode().value()).isEqualTo(409);
        // a reader cannot reorder (TEST 15: 403, no state change)
        assertThat(send(HttpMethod.POST, BLOCKS + "/reorder", R, reorder(ic, vc, ib, vb, ia, va)).getStatusCode().value()).isEqualTo(403);
        assertThat(version(ia)).isEqualTo(va);

        ResponseEntity<JsonNode> ok = send(HttpMethod.POST, BLOCKS + "/reorder", W, reorder(ic, vc, ib, vb, ia, va));
        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
        assertThat(ids(home("?channel=app"))).as("TEST 13").containsExactly(ib, ia);
        assertThat(ids(home("?channel=web"))).as("TEST 13").containsExactly(ic, ia);
        assertThat(db.getCollection("domain_events").countDocuments(new Document("type", "CONTENT_BLOCK_REORDERED")))
                .as("every moved block audited").isEqualTo(3);
    }

    @Test
    void preview_shows_drafts_and_future_content_for_one_channel_and_never_publishes() {
        String draftApp = create(block("PRODUCT_RAIL", "Draft app rail", 1, Map.of("ids", List.of("TZP-1")), "APP_ONLY", null, null)).get("blockId").asText();
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        String scheduled = publish(create(block("PRODUCT_RAIL", "Diwali", 2, Map.of("ids", List.of("TZP-2")), "BOTH", start.toString(), null)));

        assertThat(previewIds("?channel=app", R)).as("published-only, now").isEmpty();
        assertThat(previewIds("?channel=app&drafts=true", R)).containsExactly(draftApp);
        assertThat(previewIds("?channel=web&drafts=true", R)).as("APP_ONLY is not on web").isEmpty();
        assertThat(previewIds("?channel=app&drafts=true&at=" + start.plusSeconds(60), R)).containsExactly(draftApp, scheduled);
        JsonNode p = send(HttpMethod.GET, "/api/v1/admin/content/preview/home?channel=app&drafts=true", R, null).getBody();
        assertThat(p.at("/blocks/0/effectiveStatus").asText()).isEqualTo("DRAFT");

        // TEST 16: none of it is public, and previewing changed nothing
        assertThat(home("?channel=app")).isEmpty();
        assertThat(home("?channel=web")).isEmpty();
        assertThat(send(HttpMethod.GET, BLOCKS + "/" + draftApp, R, null).getBody().get("status").asText()).isEqualTo("DRAFT");

        assertThat(send(HttpMethod.GET, "/api/v1/admin/content/preview/home?channel=tv", R, null).getStatusCode().value()).isEqualTo(422);
        assertThat(send(HttpMethod.GET, "/api/v1/admin/content/preview/home?channel=app", null, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void banner_uploads_are_refused_without_storage_and_validated_before_that() {
        ResponseEntity<JsonNode> off = send(HttpMethod.POST, "/api/v1/admin/content/uploads", W, Map.of("contentType", "image/webp", "sizeBytes", 1000));
        assertThat(off.getStatusCode().value()).isEqualTo(503);
        assertThat(off.getBody().at("/error/code").asText()).isEqualTo("MEDIA_STORAGE_NOT_CONFIGURED");
        assertThat(send(HttpMethod.POST, "/api/v1/admin/content/uploads", W, Map.of("contentType", "image/svg+xml", "sizeBytes", 1000))
                .getStatusCode().value()).as("svg is never accepted").isEqualTo(422);
        assertThat(send(HttpMethod.POST, "/api/v1/admin/content/uploads", W, Map.of("contentType", "image/png", "sizeBytes", 999_999_999L))
                .getStatusCode().value()).isEqualTo(422);
        assertThat(send(HttpMethod.POST, "/api/v1/admin/content/uploads", R, Map.of("contentType", "image/png", "sizeBytes", 10))
                .getStatusCode().value()).isEqualTo(403);
    }

    // ---------- helpers ----------

    private ResponseEntity<JsonNode> send(HttpMethod m, String path, String token, Object body) {
        HttpHeaders h = token == null ? new HttpHeaders() : bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private int status(HttpMethod m, String path, String token, Object body) {
        return send(m, path, token, body).getStatusCode().value();
    }

    private static Map<String, Object> block(String type, String title, int sort, Map<String, Object> payload, String audience,
                                             String startsAt, String endsAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("sort", sort);
        m.put("payload", payload);
        if (audience != null) m.put("audience", audience);
        if (startsAt != null) m.put("startsAt", startsAt);
        if (endsAt != null) m.put("endsAt", endsAt);
        return m;
    }

    private static Map<String, Object> reorder(Object... idVersion) {
        List<Map<String, Object>> order = new ArrayList<>();
        for (int i = 0; i < idVersion.length; i += 2) order.add(Map.of("blockId", idVersion[i], "expectedVersion", idVersion[i + 1]));
        return Map.of("placement", "HOME", "order", order);
    }

    private JsonNode create(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, BLOCKS, W, body);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        return r.getBody();
    }

    private String publish(JsonNode b) {
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, BLOCKS + "/" + b.get("blockId").asText() + "/status", W,
                Map.of("to", "PUBLISHED", "expectedVersion", b.get("version").asLong()));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return b.get("blockId").asText();
    }

    private long version(String id) {
        return send(HttpMethod.GET, BLOCKS + "/" + id, R, null).getBody().get("version").asLong();
    }

    private JsonNode home(String query) {
        ResponseEntity<JsonNode> r = get("/v1/content/home" + query, JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody().get("blocks");
    }

    private static List<String> ids(JsonNode blocks) {
        List<String> out = new ArrayList<>();
        blocks.forEach(b -> out.add(b.get("blockId").asText()));
        return out;
    }

    private List<String> previewIds(String query, String token) {
        ResponseEntity<JsonNode> r = send(HttpMethod.GET, "/api/v1/admin/content/preview/home" + query, token, null);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return ids(r.getBody().get("blocks"));
    }
}
