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

    // ------------------------------------------------------------------ help centre and legal links

    private JsonNode faq(String category, String question, int sort) {
        Map<String, Object> b = block("FAQ", "FAQ " + question, sort,
                Map.of("faqCategory", category, "question", question, "answer", "Answer to " + question));
        b.put("placement", "HELP");
        return create(b);
    }

    private ResponseEntity<JsonNode> faqs(String query) {
        return get("/v1/content/faqs" + query, JsonNode.class);
    }

    @Test
    void faqs_are_typed_help_blocks_live_only_when_published_ordered_by_category_then_sort() {
        JsonNode refundLate = publish(faq("REFUND", "Refund two?", 5));
        JsonNode delivery = publish(faq("DELIVERY", "When does it arrive?", 9));
        JsonNode refundEarly = publish(faq("REFUND", "Refund one?", 1));
        faq("CLUB", "Draft only?", 1);                                      // never published

        ResponseEntity<JsonNode> all = faqs("");
        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(all.getHeaders().getCacheControl()).isEqualTo("public, max-age=60");
        List<String> order = new ArrayList<>();
        all.getBody().get("faqs").forEach(f -> order.add(f.get("faqId").asText()));
        assertThat(order).as("DELIVERY before REFUND (enum order), then sort").containsExactly(delivery.get("blockId").asText(),
                refundEarly.get("blockId").asText(), refundLate.get("blockId").asText());
        JsonNode first = all.getBody().get("faqs").get(0);
        assertThat(first.get("category").asText()).isEqualTo("DELIVERY");
        assertThat(first.get("question").asText()).isEqualTo("When does it arrive?");
        assertThat(first.get("answer").asText()).isEqualTo("Answer to When does it arrive?");
        assertThat(first.has("status") || first.has("version")).as("no admin fields leak").isFalse();

        JsonNode refunds = faqs("?category=REFUND").getBody();
        assertThat(refunds.get("faqs")).hasSize(2);
        assertThat(faqs("?category=CLUB").getBody().get("faqs")).as("a draft is not live").isEmpty();

        for (String bad : new String[]{"?category=SHIPPING", "?category=refund", "?category=", "?x=1", "?category=REFUND&category=CLUB",
                "?category=REFUND&x=1"}) {
            ResponseEntity<JsonNode> r = faqs(bad);
            assertThat(r.getStatusCode().value()).as(bad).isEqualTo(400);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        // FAQ never shows on HOME, and the home list never includes help blocks
        assertThat(homeIds()).doesNotContain(delivery.get("blockId").asText());
    }

    @Test
    void faq_placement_and_type_must_match_and_writes_are_audited() {
        Map<String, Object> onHome = block("FAQ", "Wrong place", 1, Map.of("faqCategory", "CLUB", "question", "Q?", "answer", "A."));
        assertThat(send(HttpMethod.POST, BLOCKS, W, onHome).getStatusCode().value()).isEqualTo(422);
        Map<String, Object> bannerOnHelp = block("BANNER", "Wrong place", 1, Map.of("imageAssetKey", "cms/home/a.webp", "link", "product:TZP-1"));
        bannerOnHelp.put("placement", "HELP");
        assertThat(send(HttpMethod.POST, BLOCKS, W, bannerOnHelp).getStatusCode().value()).isEqualTo(422);
        Map<String, Object> markup = block("FAQ", "Bad", 1, Map.of("faqCategory", "CLUB", "question", "Q?", "answer", "<b>A</b>"));
        markup.put("placement", "HELP");
        assertThat(send(HttpMethod.POST, BLOCKS, W, markup).getStatusCode().value()).isEqualTo(422);
        assertThat(send(HttpMethod.POST, BLOCKS, R, onHome).getStatusCode().value()).as("reader cannot write").isEqualTo(403);
        assertThat(db.getCollection("content_blocks").countDocuments()).isZero();

        JsonNode f = faq("ACCOUNT", "How do I delete my account?", 1);
        JsonNode listed = send(HttpMethod.GET, BLOCKS + "?placement=HELP", R, null).getBody();
        assertThat(listed.get("items")).hasSize(1);
        assertThat(listed.get("items").get(0).at("/payload/faqCategory").asText()).isEqualTo("ACCOUNT");
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_id", f.get("blockId").asText())
                .append("type", "CONTENT_BLOCK_CREATED"))).isEqualTo(1);
    }

    @Test
    void the_faq_read_is_admission_charged_on_its_own_route() {
        var route = com.tazzzo.catalog.consumer.ConsumerObservability.Route.CONTENT_FAQS;
        long before = charged(route);
        faqs("");
        faqs("?category=CLUB");
        assertThat(charged(route) - before).isEqualTo(2);
        long home = charged(com.tazzzo.catalog.consumer.ConsumerObservability.Route.CONTENT_HOME);
        faqs("");
        assertThat(charged(com.tazzzo.catalog.consumer.ConsumerObservability.Route.CONTENT_HOME)).isEqualTo(home);
    }

    @Test
    void legal_links_round_trip_and_only_https_is_accepted() {
        JsonNode none = get("/v1/app-config", JsonNode.class).getBody();
        assertThat(none.get("legal").get("termsUrl").isNull()).as("unset link is null").isTrue();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("storeOpen", true);
        cfg.put("maintenance", false);
        cfg.put("termsUrl", "https://tazzzo.com/terms");
        cfg.put("privacyUrl", "https://tazzzo.com/privacy");
        cfg.put("refundPolicyUrl", "https://tazzzo.com/refunds");
        cfg.put("expectedVersion", 0);
        assertThat(send(HttpMethod.PUT, "/api/v1/admin/app-config", W, cfg).getStatusCode().value()).isEqualTo(200);
        JsonNode legal = get("/v1/app-config", JsonNode.class).getBody().get("legal");
        assertThat(legal.get("termsUrl").asText()).isEqualTo("https://tazzzo.com/terms");
        assertThat(legal.get("privacyUrl").asText()).isEqualTo("https://tazzzo.com/privacy");
        assertThat(legal.get("refundPolicyUrl").asText()).isEqualTo("https://tazzzo.com/refunds");

        cfg.put("expectedVersion", 1);
        cfg.put("privacyUrl", "http://tazzzo.com/privacy");
        ResponseEntity<JsonNode> refused = send(HttpMethod.PUT, "/api/v1/admin/app-config", W, cfg);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(get("/v1/app-config", JsonNode.class).getBody().at("/legal/privacyUrl").asText()).isEqualTo("https://tazzzo.com/privacy");
    }
}
