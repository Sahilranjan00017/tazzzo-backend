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
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legal documents (terms, privacy) as CMS content: typed LEGAL blocks on HELP, at most one live per slug, served by
 * {@code GET /v1/content/legal/{slug}} to anyone, without credentials, from the same blocks the CMS edits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentLegalIT extends AbstractConsumerIT {

    static final String W = "cms-test-token";
    static final String R = "read-test-token";
    static final String BLOCKS = "/api/v1/admin/content/blocks";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_content_legal_it");
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
        db.getCollection("system_config").deleteMany(new Document("_id", new Document("$regex", "^legal_live_lock:")));
    }

    private ResponseEntity<JsonNode> send(HttpMethod m, String path, String token, Object body) {
        HttpHeaders h = bearer(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), m, new HttpEntity<>(body, h), JsonNode.class);
    }

    private static Map<String, Object> legalBody(String slug, String title, String body, String effectiveDate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("legalSlug", slug);
        payload.put("body", body);
        if (effectiveDate != null) payload.put("effectiveDate", effectiveDate);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("placement", "HELP");
        m.put("type", "LEGAL");
        m.put("title", title);
        m.put("sort", 1);
        m.put("payload", payload);
        return m;
    }

    private JsonNode draft(String slug, String title, String body, String effectiveDate, Instant startsAt, Instant endsAt) {
        Map<String, Object> m = legalBody(slug, title, body, effectiveDate);
        if (startsAt != null) m.put("startsAt", startsAt.toString());
        if (endsAt != null) m.put("endsAt", endsAt.toString());
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, BLOCKS, W, m);
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        return r.getBody();
    }

    private JsonNode draft(String slug, String title) {
        return draft(slug, title, "Clause one.\n\nClause two.", "2026-10-01", null, null);
    }

    private ResponseEntity<JsonNode> setStatus(JsonNode b, String to) {
        return send(HttpMethod.POST, BLOCKS + "/" + b.get("blockId").asText() + "/status", W,
                Map.of("to", to, "expectedVersion", b.get("version").asLong()));
    }

    private JsonNode publish(JsonNode b) {
        ResponseEntity<JsonNode> r = setStatus(b, "PUBLISHED");
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return r.getBody();
    }

    private ResponseEntity<JsonNode> legal(String slug) {
        return get("/v1/content/legal/" + slug, JsonNode.class);
    }

    private JsonNode fresh(JsonNode b) {
        return send(HttpMethod.GET, BLOCKS + "/" + b.get("blockId").asText(), R, null).getBody();
    }

    // ------------------------------------------------------------------ public read

    @Test
    void a_draft_is_not_served_and_a_published_document_is_served_to_anyone_without_credentials() {
        JsonNode terms = draft("TERMS", "Terms of Service");
        ResponseEntity<JsonNode> none = legal("terms");
        assertThat(none.getStatusCode().value()).as("a draft is never public").isEqualTo(404);
        assertThat(none.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(none.getBody().get("retryable").asBoolean()).isFalse();
        assertThat(none.getBody().get("requestId").asText()).isNotBlank();
        assertThat(none.getBody().has("request_id")).as("flat /v1 envelope: camelCase").isFalse();

        publish(terms);
        ResponseEntity<JsonNode> res = legal("terms");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).isEqualTo("public, max-age=60");
        JsonNode body = res.getBody();
        assertThat(body.get("slug").asText()).isEqualTo("terms");
        assertThat(body.get("title").asText()).isEqualTo("Terms of Service");
        assertThat(body.get("body").asText()).isEqualTo("Clause one.\n\nClause two.");
        assertThat(body.get("effectiveDate").asText()).isEqualTo("2026-10-01");
        assertThat(body.get("requestId").asText()).isNotBlank();
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("slug", "title", "body", "effectiveDate", "requestId");

        for (String token : new String[]{"bogus", R, W}) {
            assertThat(send(HttpMethod.GET, "/v1/content/legal/terms", token, null).getStatusCode().value())
                    .as("no credential is needed, and none is ever rejected: " + token).isEqualTo(200);
        }
        assertThat(legal("privacy").getStatusCode().value()).as("the other slug has no live document").isEqualTo(404);

        setStatus(fresh(terms), "DRAFT");
        assertThat(legal("terms").getStatusCode().value()).as("unpublished again").isEqualTo(404);
    }

    @Test
    void effective_date_is_optional_and_null_when_absent() {
        publish(draft("PRIVACY", "Privacy Policy", "We keep little.", null, null, null));
        JsonNode body = legal("privacy").getBody();
        assertThat(body.has("effectiveDate")).isTrue();
        assertThat(body.get("effectiveDate").isNull()).isTrue();
    }

    @Test
    void scheduled_and_expired_documents_are_not_served() {
        publish(draft("TERMS", "Later", "b", null, Instant.now().plusSeconds(3600), null));
        publish(draft("TERMS", "Earlier", "b", null, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        assertThat(legal("terms").getStatusCode().value()).isEqualTo(404);
        JsonNode live = publish(draft("TERMS", "Now", "b", null, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(1800)));
        ResponseEntity<JsonNode> res = legal("terms");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("title").asText()).isEqualTo("Now");
        assertThat(live.get("effectiveStatus").asText()).isEqualTo("LIVE");
    }

    @Test
    void unknown_slugs_are_a_flat_404_and_any_query_parameter_is_a_400() {
        publish(draft("TERMS", "Terms"));
        for (String slug : new String[]{"refunds", "Terms", "TERMS", "privacy-policy", "a".repeat(64), "x"}) {
            ResponseEntity<JsonNode> r = legal(slug);
            assertThat(r.getStatusCode().value()).as(slug).isEqualTo(404);
            assertThat(String.valueOf(r.getBody())).as(slug).contains("NOT_FOUND");
            assertThat(r.getBody().get("code").asText()).as(slug).isEqualTo("NOT_FOUND");
            assertThat(r.getBody().has("requestId")).isTrue();
        }
        assertThat(legal("terms.json").getStatusCode().value()).as("outside the single plain segment: unknown surface, still 404").isEqualTo(404);
        for (String q : new String[]{"?x=1", "?channel=app", "?channel=web", "?slug=terms", "?", "?a=1&b=2"}) {
            ResponseEntity<JsonNode> r = legal("terms" + q);
            if (q.equals("?")) {
                assertThat(r.getStatusCode().value()).as("an empty query carries no parameter").isEqualTo(200);
                continue;
            }
            assertThat(r.getStatusCode().value()).as(q).isEqualTo(400);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(r.getBody().has("requestId")).isTrue();
        }
        assertThat(legal("refunds?x=1").getStatusCode().value()).as("parameters are refused before the slug is looked at").isEqualTo(400);
        // not under the legal route: the bare path and deeper paths are unknown surfaces (404 NO_SUCH_ENDPOINT)
        for (String p : new String[]{"/v1/content/legal", "/v1/content/legal/", "/v1/content/legal/terms/x"}) {
            ResponseEntity<JsonNode> r = get(p, JsonNode.class);
            assertThat(r.getStatusCode().value()).as(p).isEqualTo(404);
        }
    }

    @Test
    void the_public_read_is_admission_charged_on_its_own_bounded_route() {
        var route = com.tazzzo.catalog.consumer.ConsumerObservability.Route.CONTENT_LEGAL;
        assertThat(route.tag()).isEqualTo("content_legal");
        long before = charged(route);
        long faqs = charged(com.tazzzo.catalog.consumer.ConsumerObservability.Route.CONTENT_FAQS);
        legal("terms");
        legal("privacy");
        legal("nonsense");
        assertThat(charged(route) - before).as("slug never becomes a tag; unknown slugs are charged too").isEqualTo(3);
        assertThat(charged(com.tazzzo.catalog.consumer.ConsumerObservability.Route.CONTENT_FAQS)).isEqualTo(faqs);
        registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.RATE_LIMIT_COST).meters().forEach(m ->
                assertThat(m.getId().getTag("route")).as("bounded route tags").doesNotContain("terms", "privacy", "nonsense"));
    }

    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry registry;

    long charged(com.tazzzo.catalog.consumer.ConsumerObservability.Route route) {
        var summary = registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.RATE_LIMIT_COST).tag("route", route.tag()).summary();
        return summary == null ? 0 : summary.count();
    }

    @Test
    void legal_blocks_never_leak_into_the_faq_or_home_reads() {
        publish(draft("TERMS", "Terms"));
        assertThat(get("/v1/content/faqs", JsonNode.class).getBody().get("faqs")).isEmpty();
        assertThat(get("/v1/content/home", JsonNode.class).getBody().get("blocks")).isEmpty();
    }

    // ------------------------------------------------------------------ the one-live rule

    @Test
    void a_second_overlapping_published_document_for_the_same_slug_is_refused_with_a_conflict() {
        JsonNode first = publish(draft("TERMS", "Terms v1"));
        JsonNode second = draft("TERMS", "Terms v2");
        ResponseEntity<JsonNode> refused = setStatus(second, "PUBLISHED");
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody().at("/error/code").asText()).isEqualTo("STATE_CONFLICT");
        assertThat(refused.getBody().at("/error/message").asText()).contains(first.get("blockId").asText());
        assertThat(fresh(second).get("status").asText()).as("nothing was written").isEqualTo("DRAFT");
        assertThat(fresh(second).get("version").asLong()).isEqualTo(second.get("version").asLong());
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_id", second.get("blockId").asText())
                .append("type", "CONTENT_BLOCK_PUBLISHED"))).as("a refused write leaves no audit row").isZero();
        assertThat(legal("terms").getBody().get("title").asText()).isEqualTo("Terms v1");

        // the other slug is independent
        publish(draft("PRIVACY", "Privacy v1"));
        // a scheduled successor that overlaps is refused too; one that starts when the first ends is accepted
        Instant cut = Instant.now().plusSeconds(86_400).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        JsonNode bounded = send(HttpMethod.PUT, BLOCKS + "/" + first.get("blockId").asText(), W, update(first, "Terms v1", null, cut)).getBody();
        assertThat(bounded.get("endsAt").asText()).isEqualTo(cut.toString());
        JsonNode overlapping = draft("TERMS", "Terms v2", "b", null, cut.minusSeconds(1), null);
        assertThat(setStatus(overlapping, "PUBLISHED").getStatusCode().value()).as("overlaps by one second").isEqualTo(409);
        JsonNode successor = draft("TERMS", "Terms v3", "b", null, cut, null);
        assertThat(setStatus(successor, "PUBLISHED").getStatusCode().value()).as("starts exactly at the predecessor's end").isEqualTo(200);
        assertThat(legal("terms").getBody().get("title").asText()).as("v1 is still the live one until the cut").isEqualTo("Terms v1");
    }

    private Map<String, Object> update(JsonNode b, String title, Instant startsAt, Instant endsAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("sort", b.get("sort").asInt());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("legalSlug", b.at("/payload/legalSlug").asText());
        payload.put("body", b.at("/payload/body").asText());
        if (b.at("/payload/effectiveDate").isTextual()) payload.put("effectiveDate", b.at("/payload/effectiveDate").asText());
        m.put("payload", payload);
        if (startsAt != null) m.put("startsAt", startsAt.toString());
        if (endsAt != null) m.put("endsAt", endsAt.toString());
        m.put("expectedVersion", b.get("version").asLong());
        return m;
    }

    @Test
    void editing_a_published_document_cannot_create_an_overlap_but_editing_a_draft_can() {
        JsonNode terms = publish(draft("TERMS", "Terms"));
        JsonNode privacy = publish(draft("PRIVACY", "Privacy"));
        // a draft may hold anything: it is not live
        JsonNode otherDraft = draft("TERMS", "Terms next");
        assertThat(send(HttpMethod.PUT, BLOCKS + "/" + otherDraft.get("blockId").asText(), W, update(otherDraft, "Terms next 2", null, null))
                .getStatusCode().value()).isEqualTo(200);

        // re-pointing a published PRIVACY block at TERMS would make two live TERMS documents
        Map<String, Object> repoint = update(privacy, "Privacy", null, null);
        ((Map<String, Object>) repoint.get("payload")).put("legalSlug", "TERMS");
        ResponseEntity<JsonNode> r = send(HttpMethod.PUT, BLOCKS + "/" + privacy.get("blockId").asText(), W, repoint);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(fresh(privacy).at("/payload/legalSlug").asText()).isEqualTo("PRIVACY");

        // editing a published document in place (same window) is fine
        assertThat(send(HttpMethod.PUT, BLOCKS + "/" + terms.get("blockId").asText(), W, update(terms, "Terms (edited)", null, null))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(legal("terms").getBody().get("title").asText()).isEqualTo("Terms (edited)");
    }

    @Test
    void replacing_a_document_is_unpublish_then_publish_and_archived_or_expired_ones_do_not_count() {
        JsonNode v1 = publish(draft("TERMS", "Terms v1"));
        JsonNode v2 = draft("TERMS", "Terms v2");
        assertThat(setStatus(v2, "PUBLISHED").getStatusCode().value()).isEqualTo(409);
        JsonNode v1Draft = setStatus(v1, "DRAFT").getBody();
        publish(v2);
        assertThat(legal("terms").getBody().get("title").asText()).isEqualTo("Terms v2");
        assertThat(setStatus(v1Draft, "PUBLISHED").getStatusCode().value()).as("v1 can't come back while v2 is live").isEqualTo(409);
        JsonNode v1Archived = setStatus(v1Draft, "ARCHIVED").getBody();
        assertThat(v1Archived.get("status").asText()).isEqualTo("ARCHIVED");

        // an expired published document no longer overlaps anything that starts after it ended
        publish(draft("PRIVACY", "Old privacy", "b", null, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600)));
        assertThat(setStatus(draft("PRIVACY", "New privacy"), "PUBLISHED").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void if_legacy_data_holds_two_live_documents_the_most_recently_updated_wins() {
        Date older = Date.from(Instant.now().minusSeconds(600));
        Date newer = Date.from(Instant.now().minusSeconds(60));
        for (Object[] row : new Object[][]{{"CB_legacyAAAAAAAAAAAAAAAA", "Old terms", older}, {"CB_legacyBBBBBBBBBBBBBBBB", "New terms", newer}}) {
            db.getCollection("content_blocks").insertOne(new Document("_id", row[0]).append("placement", "HELP").append("type", "LEGAL")
                    .append("title", row[1]).append("sort", 1).append("status", "PUBLISHED").append("audience", "BOTH")
                    .append("payload", new Document("legalSlug", "TERMS").append("body", "b-" + row[1]))
                    .append("version", 1L).append("createdAt", older).append("updatedAt", row[2]));
        }
        for (int i = 0; i < 3; i++) {
            assertThat(legal("terms").getBody().get("title").asText()).isEqualTo("New terms");
        }
        // and a tie on updatedAt resolves by id, not by storage order
        db.getCollection("content_blocks").updateOne(new Document("_id", "CB_legacyAAAAAAAAAAAAAAAA"), new Document("$set", new Document("updatedAt", newer)));
        assertThat(legal("terms").getBody().get("title").asText()).isEqualTo("New terms");
    }

    @Test
    void concurrent_publishes_of_the_same_slug_let_exactly_one_win() throws Exception {
        JsonNode a = draft("TERMS", "Terms A");
        JsonNode b = draft("TERMS", "Terms B");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Integer>> jobs = List.of(
                    () -> { go.await(); return setStatus(a, "PUBLISHED").getStatusCode().value(); },
                    () -> { go.await(); return setStatus(b, "PUBLISHED").getStatusCode().value(); });
            List<Future<Integer>> futures = jobs.stream().map(pool::submit).toList();
            go.countDown();
            List<Integer> codes = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.getCollection("content_blocks").countDocuments(new Document("type", "LEGAL").append("status", "PUBLISHED"))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ admin validation and permissions

    @Test
    void admin_validation_permissions_and_audit() {
        Map<String, Object> ok = legalBody("TERMS", "Terms", "Body", "2026-10-01");
        assertThat(send(HttpMethod.POST, BLOCKS, R, ok).getStatusCode().value()).as("reader cannot write").isEqualTo(403);
        assertThat(send(HttpMethod.POST, BLOCKS, null, ok).getStatusCode().value()).isEqualTo(401);
        for (Map<String, Object> bad : List.of(
                legalBody("REFUNDS", "T", "b", null), legalBody("terms", "T", "b", null), legalBody("TERMS", "T", "", null),
                legalBody("TERMS", "T", "a <b>x</b>", null), legalBody("TERMS", "T", "a\tb", null), legalBody("TERMS", "T", "b", "01-10-2026"),
                legalBody("TERMS", "T", "b", "2026-02-30"), legalBody("TERMS", "T", "x".repeat(60_001), null),
                legalBody("TERMS", "x".repeat(81), "b", null))) {
            ResponseEntity<JsonNode> r = send(HttpMethod.POST, BLOCKS, W, bad);
            assertThat(r.getStatusCode().value()).as(String.valueOf(bad.get("payload"))).isEqualTo(422);
            assertThat(r.getBody().at("/error/code").asText()).isEqualTo("INVALID_CONTENT");
        }
        Map<String, Object> onHome = new LinkedHashMap<>(ok);
        onHome.put("placement", "HOME");
        assertThat(send(HttpMethod.POST, BLOCKS, W, onHome).getStatusCode().value()).as("LEGAL belongs on HELP").isEqualTo(422);
        Map<String, Object> targeted = new LinkedHashMap<>(ok);
        targeted.put("audience", "APP_ONLY");
        assertThat(send(HttpMethod.POST, BLOCKS, W, targeted).getStatusCode().value()).as("HELP is always BOTH").isEqualTo(422);
        assertThat(db.getCollection("content_blocks").countDocuments()).isZero();

        Map<String, Object> max = legalBody("PRIVACY", "Privacy", "p".repeat(60_000), null);
        JsonNode created = send(HttpMethod.POST, BLOCKS, W, max).getBody();
        assertThat(created.at("/payload/body").asText()).hasSize(60_000);
        assertThat(created.get("audience").asText()).isEqualTo("BOTH");
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        JsonNode listed = send(HttpMethod.GET, BLOCKS + "?placement=HELP", R, null).getBody();
        assertThat(listed.get("items")).hasSize(1);
        assertThat(listed.get("items").get(0).get("type").asText()).isEqualTo("LEGAL");
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_id", created.get("blockId").asText())
                .append("type", "CONTENT_BLOCK_CREATED"))).isEqualTo(1);
        // the type and its slug are fixed fields of the payload: an FAQ payload cannot be smuggled into a LEGAL block
        Map<String, Object> smuggled = update(created, "Privacy", null, null);
        ((Map<String, Object>) smuggled.get("payload")).put("question", "Q?");
        assertThat(send(HttpMethod.PUT, BLOCKS + "/" + created.get("blockId").asText(), W, smuggled).getStatusCode().value()).isEqualTo(422);
    }
}
