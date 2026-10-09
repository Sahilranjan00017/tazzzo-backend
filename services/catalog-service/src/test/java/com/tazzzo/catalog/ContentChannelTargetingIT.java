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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multichannel D1–D3 over real HTTP: APP_ONLY content reaches only {@code ?channel=app}, WEB_ONLY only {@code ?channel=web},
 * BOTH (and every legacy block without an audience) reaches both and an unidentified platform; the filter is the backend's;
 * draft, unpublished and expired targeted content is never public; HELP content stays global; the admin API round-trips,
 * filters and audits the audience, and an older client's update never erases it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentChannelTargetingIT extends AbstractConsumerIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String BLOCKS = "/api/v1/admin/content/blocks";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_content_channel_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> W);
        r.add("tazzzo.auth.read-token", () -> R);
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString("channel-cursor-fixture-key-32b!!".getBytes(StandardCharsets.UTF_8)));
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

    // ---------- the public contract: TEST 6 / 7 / 8 / 9 / 19 at the origin ----------

    @Test
    void app_only_web_only_and_both_reach_exactly_their_channels() {
        String app = publish(create(block("BANNER", "App launch offer", 1, banner("cms/home/app.webp"), "APP_ONLY")));
        String web = publish(create(block("PRODUCT_RAIL", "Web picks", 2, Map.of("ids", List.of("TZP-1")), "WEB_ONLY")));
        String both = publish(create(block("CATEGORY_GRID", "Aisles", 3, Map.of("ids", List.of("TZC-000001")), "BOTH")));
        String implicit = publish(create(block("PRODUCT_RAIL", "Default audience", 4, Map.of("ids", List.of("TZP-2")), null)));
        String legacy = legacyPublishedBlock("Pre-multichannel rail", 5);

        assertThat(homeIds("")).as("an unidentified platform sees BOTH only").containsExactly(both, implicit, legacy);
        assertThat(homeIds("?channel=app")).as("TEST 6/8: app sees app-only and both").containsExactly(app, both, implicit, legacy);
        assertThat(homeIds("?channel=web")).as("TEST 7/8: web sees web-only and both").containsExactly(web, both, implicit, legacy);
    }

    @Test
    void an_explicit_null_audience_reads_as_both_and_an_unreadable_one_hides_only_that_block() {
        String both = publish(create(block("CATEGORY_GRID", "Aisles", 1, Map.of("ids", List.of("TZC-000001")), "BOTH")));
        String nulled = legacyPublishedBlock("Null audience", 2);
        db.getCollection("content_blocks").updateOne(new Document("_id", nulled), new Document("$set", new Document("audience", null)));
        String corrupt = legacyPublishedBlock("Corrupt audience", 3);
        db.getCollection("content_blocks").updateOne(new Document("_id", corrupt), new Document("$set", new Document("audience", "EVERYONE")));

        // query and domain agree: an explicit null is legacy BOTH on every channel and in the admin BOTH filter
        assertThat(homeIds("")).containsExactly(both, nulled);
        assertThat(homeIds("?channel=app")).containsExactly(both, nulled);
        assertThat(homeIds("?channel=web")).containsExactly(both, nulled);
        assertThat(ids(send(HttpMethod.GET, BLOCKS + "?audience=BOTH", R, null).getBody())).contains(both, nulled);
    }

    @Test
    void an_older_client_update_does_not_rewrite_a_legacy_document() {
        String legacy = legacyPublishedBlock("Legacy rail", 1);
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("title", "Legacy rail (renamed)");
        upd.put("sort", 1);
        upd.put("payload", Map.of("ids", List.of("TZP-9")));
        upd.put("expectedVersion", 1L);
        ResponseEntity<JsonNode> r = send(HttpMethod.PUT, BLOCKS + "/" + legacy, W, upd);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(r.getBody().get("audience").asText()).isEqualTo("BOTH");
        Document stored = db.getCollection("content_blocks").find(new Document("_id", legacy)).first();
        assertThat(stored.getString("title")).isEqualTo("Legacy rail (renamed)");
        assertThat(stored.containsKey("audience")).as("no silent, unaudited rewrite").isFalse();
    }

    @Test
    void the_channel_parameter_is_a_closed_choice_and_the_only_parameter() {
        for (String q : new String[]{"?channel=ios", "?channel=APP", "?channel=", "?channel=app&channel=web", "?channel=app&x=1", "?x=1"}) {
            ResponseEntity<JsonNode> r = get("/v1/content/home" + q, JsonNode.class);
            assertThat(r.getStatusCode().value()).as(q + " -> " + r.getBody()).isEqualTo(400);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    void draft_unpublished_and_expired_targeted_content_is_never_public() {
        JsonNode draft = create(block("BANNER", "Draft", 1, banner("cms/home/d.webp"), "APP_ONLY"));
        JsonNode live = create(block("BANNER", "Live", 2, banner("cms/home/l.webp"), "APP_ONLY"));
        String liveId = publish(live);
        Map<String, Object> expired = block("BANNER", "Expired", 3, banner("cms/home/e.webp"), "WEB_ONLY");
        expired.put("startsAt", "2026-01-01T00:00:00Z");
        expired.put("endsAt", "2026-01-02T00:00:00Z");
        publish(create(expired));
        assertThat(homeIds("?channel=app")).containsExactly(liveId);
        assertThat(homeIds("?channel=web")).as("TEST 19: expired is not displayed").isEmpty();

        // TEST 9: unpublish removes it from every channel at the origin (downstream caches lag at most max-age=60)
        JsonNode current = send(HttpMethod.GET, BLOCKS + "/" + liveId, W, null).getBody();
        ResponseEntity<JsonNode> unpublished = send(HttpMethod.POST, BLOCKS + "/" + liveId + "/status", W,
                Map.of("to", "DRAFT", "expectedVersion", current.get("version").asLong()));
        assertThat(unpublished.getStatusCode().value()).isEqualTo(200);
        assertThat(homeIds("?channel=app")).isEmpty();
        assertThat(homeIds("?channel=web")).isEmpty();
        assertThat(homeIds("")).isEmpty();
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void help_content_is_global_and_unaffected() {
        Map<String, Object> faq = new LinkedHashMap<>(block("FAQ", "Delivery hours", 1,
                Map.of("faqCategory", "DELIVERY", "question", "When do you deliver?", "answer", "Every day."), "WEB_ONLY"));
        faq.put("placement", "HELP");
        ResponseEntity<JsonNode> refused = send(HttpMethod.POST, BLOCKS, W, faq);
        assertThat(refused.getStatusCode().value()).as(String.valueOf(refused.getBody())).isEqualTo(422);
        assertThat(refused.getBody().at("/error/code").asText()).isEqualTo("INVALID_CONTENT");
        faq.put("audience", "BOTH");
        publish(create(faq));
        faq.remove("audience");
        faq.put("title", "Returns");
        faq.put("sort", 2);
        faq.put("payload", Map.of("faqCategory", "REFUND", "question", "How do I return?", "answer", "In the app."));
        publish(create(faq));
        JsonNode faqs = get("/v1/content/faqs", JsonNode.class).getBody();
        assertThat(faqs.get("faqs")).hasSize(2);
        assertThat(get("/v1/content/faqs?channel=app", JsonNode.class).getStatusCode().value()).as("faqs take no channel").isEqualTo(400);
    }

    // ---------- the admin contract ----------

    @Test
    void admin_round_trips_filters_audits_and_an_older_client_never_erases_the_audience() {
        JsonNode created = create(block("PRODUCT_RAIL", "App rail", 1, Map.of("ids", List.of("TZP-1")), "APP_ONLY"));
        assertThat(created.get("audience").asText()).isEqualTo("APP_ONLY");
        String id = created.get("blockId").asText();
        assertThat(create(block("PRODUCT_RAIL", "Plain", 2, Map.of("ids", List.of("TZP-2")), null)).get("audience").asText())
                .as("absent on create = BOTH").isEqualTo("BOTH");
        String legacy = legacyPublishedBlock("Legacy", 3);
        assertThat(send(HttpMethod.GET, BLOCKS + "/" + legacy, R, null).getBody().get("audience").asText())
                .as("a legacy document reads as BOTH").isEqualTo("BOTH");

        ResponseEntity<JsonNode> bad = send(HttpMethod.POST, BLOCKS, W, block("PRODUCT_RAIL", "Bad", 4, Map.of("ids", List.of("TZP-3")), "EVERYONE"));
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(bad.getBody().at("/error/code").asText()).isEqualTo("INVALID_CONTENT");

        assertThat(ids(send(HttpMethod.GET, BLOCKS + "?audience=APP_ONLY", R, null).getBody())).containsExactly(id);
        assertThat(ids(send(HttpMethod.GET, BLOCKS + "?audience=BOTH", R, null).getBody())).as("BOTH includes legacy").hasSize(2).contains(legacy);
        assertThat(ids(send(HttpMethod.GET, BLOCKS + "?audience=WEB_ONLY", R, null).getBody())).isEmpty();
        assertThat(send(HttpMethod.GET, BLOCKS + "?audience=nope", R, null).getStatusCode().value()).isEqualTo(422);

        // an older CMS build updates without the field: the audience is kept
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("title", "App rail (renamed)");
        upd.put("sort", 1);
        upd.put("payload", Map.of("ids", List.of("TZP-1")));
        upd.put("expectedVersion", created.get("version").asLong());
        JsonNode kept = send(HttpMethod.PUT, BLOCKS + "/" + id, W, upd).getBody();
        assertThat(kept.get("audience").asText()).isEqualTo("APP_ONLY");

        // and a deliberate change is applied and audited with the transition
        upd.put("expectedVersion", kept.get("version").asLong());
        upd.put("audience", "WEB_ONLY");
        JsonNode changed = send(HttpMethod.PUT, BLOCKS + "/" + id, W, upd).getBody();
        assertThat(changed.get("audience").asText()).isEqualTo("WEB_ONLY");
        List<Document> events = db.getCollection("domain_events").find(new Document("aggregate_id", id)).sort(new Document("ts", 1)).into(new ArrayList<>());
        assertThat(events).extracting(e -> e.getString("type")).containsExactly("CONTENT_BLOCK_CREATED", "CONTENT_BLOCK_UPDATED", "CONTENT_BLOCK_UPDATED");
        assertThat(events.get(0).get("detail", Document.class).getString("audience")).isEqualTo("APP_ONLY");
        assertThat(events.get(1).get("detail", Document.class).containsKey("audience")).as("no change, no detail").isFalse();
        assertThat(events.get(2).get("detail", Document.class).getString("audience")).isEqualTo("WEB_ONLY");
        assertThat(events.get(2).get("detail", Document.class).getString("from")).isEqualTo("APP_ONLY");
    }

    // ---------- helpers ----------

    private ResponseEntity<JsonNode> send(HttpMethod m, String path, String token, Object body) {
        HttpHeaders h = bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private static Map<String, Object> banner(String key) {
        return Map.of("imageAssetKey", key, "link", "category:TZC-000001");
    }

    private static Map<String, Object> block(String type, String title, int sort, Map<String, Object> payload, String audience) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("sort", sort);
        m.put("payload", payload);
        if (audience != null) m.put("audience", audience);
        return m;
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

    /** A block written before the audience existed: no field at all. */
    private String legacyPublishedBlock(String title, int sort) {
        String id = "CB_legacy" + sort + "xxxxxxxxxxxxxxxx";
        Date now = Date.from(Instant.now());
        db.getCollection("content_blocks").insertOne(new Document("_id", id).append("placement", "HOME").append("type", "PRODUCT_RAIL")
                .append("title", title).append("sort", sort).append("status", "PUBLISHED")
                .append("payload", new Document("ids", List.of("TZP-9"))).append("version", 1L).append("createdAt", now).append("updatedAt", now));
        return id;
    }

    private List<String> homeIds(String query) {
        ResponseEntity<JsonNode> r = get("/v1/content/home" + query, JsonNode.class);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        List<String> out = new ArrayList<>();
        r.getBody().get("blocks").forEach(b -> out.add(b.get("blockId").asText()));
        return out;
    }

    private static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.get("items").forEach(b -> out.add(b.get("blockId").asText()));
        return out;
    }
}
