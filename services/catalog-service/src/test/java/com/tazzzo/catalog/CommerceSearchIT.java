package com.tazzzo.catalog;

import com.tazzzo.common.audit.TestActors;
import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.commerce.read.RebuildOutcome;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.money.Currency;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.media.MediaService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-G — GET /v1/search over real HTTP, Mongo and Redis: tokens, scope, eligibility truth, paging, grammar, backfill. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceSearchIT extends AbstractConsumerIT {

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "search-cursor-fixture-key-32bytes!".getBytes(StandardCharsets.UTF_8));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String V_BASMATI = "TZV-000001";
    private static final String V_OTHER = "TZV-000002";
    private static final String PIN = "560001";

    @org.springframework.beans.factory.annotation.Autowired com.mongodb.client.MongoClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_search_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> FIXTURE_KEY_B64);
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.tazzzo.com");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    private PricingService pricing() { return new PricingService(new Tx(client), new WritePath(db), CLOCK); }
    private MediaService media() { return new MediaService(new Tx(client), new WritePath(db), CLOCK); }
    private ProductCardProjectionService projector() {
        return new ProductCardProjectionService(new CatalogCardReader(db), pricing(), media(), db, CLOCK);
    }

    private void seed(String sku, String vertical, String title, String brand, boolean eligible) {
        if (eligible) {
            eligibleProduct(sku, vertical);
        } else {
            product(sku, vertical, "active", "provisional", "single");
        }
        db.getCollection("products").updateOne(Filters.eq("_id", sku),
                Updates.combine(Updates.set("title", title), Updates.set("brand_code", brand)));
        pricing().upsertPrice(new UpsertPriceCommand(sku, 10000L, 12000L, Currency.INR, null, null, "seed", null));
        projector().rebuildOne(sku);
    }

    @BeforeAll
    void seedAll() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        com.tazzzo.catalog.migration.IndexCatalog.PRODUCT_CARD_SEARCH_SPEC.create(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        seed("TZP-S001", V_BASMATI, "India Gate Basmati Rice 5kg", "INDIAGATE", true);
        seed("TZP-S002", V_BASMATI, "Daawat Rozana Basmati Rice 1kg", "DAAWAT", true);
        seed("TZP-S003", V_BASMATI, "Fortune Everyday Basmati 10kg", "FORTUNE", true);
        seed("TZP-S004", V_OTHER, "India Gate Biryani Basmati", "INDIAGATE", true);
        // eligible products in a vertical that is NOT in the release: lowest ids, so they would crowd page one
        for (String ghost : new String[]{"TZP-S000A", "TZP-S000B", "TZP-S000C"}) {
            seed(ghost, "TZV-999999", "Basmati ghost", "GHOST", true);
        }
        seed("TZP-S005", V_BASMATI, "Basmati Rice provisional", "NOPE", false);   // not consumer-eligible
        // an eligible product whose projection row is STALE: it says basmati, the catalogue no longer does
        seed("TZP-S006", V_BASMATI, "Basmati Rice old title", "OLD", true);
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-S006"), Updates.set("classification.status", "provisional"));
    }

    private ResponseEntity<JsonNode> search(String q) {
        return get("/v1/search?" + q, JsonNode.class);
    }

    private List<String> ids(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("items").forEach(i -> out.add(i.get("skuId").asText()));
        return out;
    }

    private void assertError(String q, int status, String code) {
        ResponseEntity<JsonNode> res = search(q);
        assertThat(res.getStatusCode().value()).as(q).isEqualTo(status);
        assertThat(res.getBody().get("code").asText()).isEqualTo(code);
    }

    @Test void every_token_must_prefix_match_and_results_are_eligible_products_only() {
        ResponseEntity<JsonNode> res = search("q=basmati");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(ids(res.getBody())).containsExactly("TZP-S001", "TZP-S002", "TZP-S003", "TZP-S004");
        assertThat(res.getBody().get("resolvedReleaseId").asText()).isEqualTo("R1");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        assertThat(ids(search("q=india+gate").getBody())).containsExactly("TZP-S001", "TZP-S004");
        assertThat(ids(search("q=DAAW+roz").getBody())).as("case-insensitive prefixes").containsExactly("TZP-S002");
        assertThat(ids(search("q=indiagate").getBody())).as("brand code is searchable").containsExactly("TZP-S001", "TZP-S004");
        assertThat(ids(search("q=basmati+fortune+gate").getBody())).as("ALL tokens must match one card").isEmpty();
        assertThat(ids(search("q=zzz").getBody())).isEmpty();
        assertThat(ids(search("q=asmati").getBody())).as("a token is matched as a PREFIX, never as a substring").isEmpty();
        assertThat(ids(search("q=basmati&page_size=2").getBody())).as("out-of-release products never occupy a page slot")
                .containsExactly("TZP-S001", "TZP-S002");
        assertThat(res.getBody().toString().toLowerCase()).doesNotContain("search_tokens").doesNotContain("fulfillment");
    }

    @Test void pages_are_keyset_bound_to_the_query_and_never_repeat_or_skip() {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode body = search("q=basmati&page_size=3" + (cursor == null ? "" : "&cursor=" + cursor)).getBody();
            seen.addAll(ids(body));
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
            assertThat(++pages).isLessThanOrEqualTo(5);
        } while (cursor != null);
        assertThat(seen).containsExactly("TZP-S001", "TZP-S002", "TZP-S003", "TZP-S004");
        assertThat(pages).isEqualTo(2);

        String first = search("q=basmati&page_size=3").getBody().get("nextCursor").asText();
        assertError("q=rice&page_size=3&cursor=" + first, 400, "INVALID_CURSOR");        // another query
        assertError("q=basmati&page_size=2&cursor=" + first, 400, "INVALID_CURSOR");     // another page size
        assertError("q=basmati&page_size=3&pin=" + PIN + "&cursor=" + first, 400, "INVALID_CURSOR");  // another location
        assertError("q=basmati&cursor=not-a-cursor", 400, "INVALID_CURSOR");
    }

    @Test void the_request_grammar_is_closed() {
        assertError("", 400, "INVALID_REQUEST");
        assertError("q=", 400, "INVALID_REQUEST");
        assertError("q=a", 400, "INVALID_REQUEST");
        assertError("q=" + "x".repeat(65), 400, "INVALID_REQUEST");
        assertError("q=aa+bb+cc+dd+ee+ff", 400, "INVALID_REQUEST");
        assertError("q=basmati&page_size=0", 400, "INVALID_REQUEST");
        assertError("q=basmati&page_size=51", 400, "INVALID_REQUEST");
        assertError("q=basmati&page_size=abc", 400, "INVALID_REQUEST");
        assertError("q=basmati&lat=12.9&lng=77.6", 400, "INVALID_REQUEST");
        assertThat(search("q=basmati&pin=" + PIN).getStatusCode().value()).isEqualTo(200);
        assertThat(search("q=basmati&pin=12").getStatusCode().value()).isEqualTo(400);
    }

    @Test void a_stale_projection_row_cannot_show_an_ineligible_product_and_an_old_row_is_backfilled() {
        // TZP-S006 still has a card row saying "basmati" but the catalogue demoted it to provisional: the search must not show it
        assertThat(db.getCollection("product_card_base").countDocuments(Filters.eq("sku_id", "TZP-S006"))).isEqualTo(1);
        assertThat(ids(search("q=old").getBody())).isEmpty();

        // a row written before search existed (no search_tokens) is invisible until rebuilt, then found; a rebuild
        // of an unchanged product is otherwise a NOOP, so the backfill must be an explicit exception to that rule
        db.getCollection("product_card_base").updateOne(Filters.eq("sku_id", "TZP-S003"), Updates.unset("search_tokens"));
        assertThat(ids(search("q=fortune").getBody())).isEmpty();
        assertThat(projector().rebuildOne("TZP-S003")).isEqualTo(RebuildOutcome.UPDATED);
        assertThat(ids(search("q=fortune").getBody())).containsExactly("TZP-S003");
        assertThat(projector().rebuildOne("TZP-S003")).as("now searchable and unchanged: NOOP again")
                .isEqualTo(RebuildOutcome.NOOP);
        Document row = db.getCollection("product_card_base").find(Filters.eq("sku_id", "TZP-S003")).first();
        assertThat(row.getList("search_tokens", String.class)).contains("fortune", "everyday", "basmati", "10kg");
    }

    @Test void search_is_a_public_route_and_the_query_plan_uses_the_token_index() {
        assertThat(search("q=basmati").getHeaders().getFirst("X-Request-Id")).startsWith("req_");
        Document explain = db.getCollection("product_card_base")
                .find(Filters.and(Filters.regex("search_tokens", "^basm"), Filters.in("vertical_id", List.of(V_BASMATI))))
                .sort(new Document("sku_id", 1)).explain();
        assertThat(explain.toJson()).contains("card_search_tokens").doesNotContain("COLLSCAN");
    }
}
