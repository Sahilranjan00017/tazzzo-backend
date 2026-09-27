package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
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
import java.util.Base64;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10B — the public /v1 commerce read contract over real HTTP, Mongo and Redis, with projection
 * freshness ENABLED (so the list endpoint is ready) and a configured media base + cursor key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceApiIT extends AbstractConsumerIT {

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "commerce-cursor-fixture-key-32b!!".getBytes(StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String C_RICE = "TZC-000001";
    private static final String V_BASMATI = "TZV-000001";
    private static final String MEDIA_BASE = "https://cdn.tazzzo.com";
    private static final String PIN = "560001";

    @org.springframework.beans.factory.annotation.Autowired
    com.mongodb.client.MongoClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_commerce_api_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> FIXTURE_KEY_B64);
        r.add("tazzzo.freshness.enabled", () -> "true");         // list readiness gate satisfied
        r.add("tazzzo.media.public-base-url", () -> MEDIA_BASE);
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    private PricingService pricing() { return new PricingService(new Tx(client), new WritePath(db), CLOCK); }
    private MediaService media() { return new MediaService(new Tx(client), new WritePath(db), CLOCK); }
    private InventoryService inventory() { return new InventoryService(new Tx(client), new WritePath(db), CLOCK); }
    private ServiceabilityService serviceability() {
        return new ServiceabilityService(new Tx(client), db, new DomainAudit(db, CLOCK), CLOCK);
    }
    private ProductCardProjectionService projector() {
        return new ProductCardProjectionService(new CatalogCardReader(db), pricing(), media(), db, CLOCK);
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        // three eligible products under Basmati, priced, projection built
        for (int i = 1; i <= 3; i++) {
            String sku = String.format("TZP-L%03d", i);
            eligibleProduct(sku, V_BASMATI);
            pricing().upsertPrice(new UpsertPriceCommand(sku, 10000L + i, 12000L + i,
                    Currency.INR, null, null, "seed", null));
            projector().rebuildOne(sku);
        }
        // serviceable PIN -> area SA-1 -> fulfillment FL-1, with stock so a card is buyable
        serviceability().upsertServiceArea(new UpsertServiceAreaCommand(
                PIN, "SA-1", List.of(new ServiceabilityRoute("FL-1", 0, true)), "seed", null));
        inventory().setInventory(new SetInventoryCommand("TZP-L001", "FL-1", 50, 2, 10, "seed", null));
    }

    private JsonNode ok(String path) {
        ResponseEntity<JsonNode> res = get(path, JsonNode.class);
        assertThat(res.getStatusCode().value()).as(path + " -> " + res.getBody()).isEqualTo(200);
        return res.getBody();
    }

    private void assertNoInternalLeak(String json) {
        String j = json.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("fulfillment", "onhand", "on_hand", "reserved",
                "assetkey", "asset_key", "source_versions", "projection_version", "internal_key",
                "purchase", "supplier", "cost_price")) {
            assertThat(j).as("public JSON must not leak " + forbidden).doesNotContain(forbidden);
        }
    }

    // ---------- categories ----------

    @Test void categories_returns_nodes_release_and_request_id() {
        ResponseEntity<JsonNode> res = get("/v1/categories", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = res.getBody();
        assertThat(body.at("/resolvedReleaseId").asText()).isEqualTo("R1");
        assertThat(body.at("/items").isArray()).isTrue();
        assertThat(body.at("/requestId").asText()).startsWith("req_");
        assertThat(res.getHeaders().getFirst("Cache-Control")).contains("public").contains("max-age=300");
        assertThat(res.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
    }

    // ---------- category products ----------

    @Test void category_products_returns_current_priced_cards_no_leaks() {
        JsonNode body = ok("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2");
        assertThat(body.at("/resolvedReleaseId").asText()).isEqualTo("R1");
        assertThat(body.at("/items").size()).isEqualTo(2);
        assertThat(body.at("/requestId").asText()).startsWith("req_");
        JsonNode first = body.at("/items").get(0);
        assertThat(first.at("/skuId").asText()).startsWith("TZP-");
        assertThat(first.at("/name").asText()).isNotEmpty();
        assertThat(first.at("/stockState").asText()).isIn("IN_STOCK", "LOW_STOCK", "OUT_OF_STOCK", "UNKNOWN");
        assertThat(first.at("/sellingPricePaise").isInt() || first.at("/sellingPricePaise").isLong()
                || first.at("/sellingPricePaise").isMissingNode()).isTrue();
        assertThat(body.at("/serviceArea/serviceable").asBoolean()).isTrue();
        assertThat(body.at("/hasMore").asBoolean()).isTrue();
        assertThat(body.at("/nextCursor").asText()).isNotEmpty();
        assertNoInternalLeak(body.toString());
        // buyable card carries the CURRENT canonical price (10001 for TZP-L001)
        assertThat(body.at("/items").get(0).at("/sellingPricePaise").asLong()).isEqualTo(10001L);
    }

    @Test void category_products_lat_lng_is_400() {
        assertError("/v1/categories/" + C_RICE + "/products?lat=12.9", 400, "INVALID_REQUEST");
    }

    @Test void category_products_bad_cursor_is_400_invalid_cursor() {
        assertError("/v1/categories/" + C_RICE + "/products?cursor=not-a-real-cursor", 400, "INVALID_CURSOR");
    }

    // ---------- product detail ----------

    @Test void product_detail_returns_current_price_and_request_id_no_leaks() {
        JsonNode body = ok("/v1/products/TZP-L001?pin=" + PIN);
        assertThat(body.at("/skuId").asText()).isEqualTo("TZP-L001");
        assertThat(body.at("/name").asText()).isNotEmpty();
        assertThat(body.at("/sellingPricePaise").asLong()).isEqualTo(10001L); // current canonical
        assertThat(body.at("/resolvedReleaseId").asText()).isEqualTo("R1");
        assertThat(body.at("/requestId").asText()).startsWith("req_");
        assertThat(body.at("/buyable").isBoolean()).isTrue();
        assertNoInternalLeak(body.toString());
    }

    @Test void product_detail_current_price_overrides_stale_projection() {
        // TZP-L002 base was built at the seed price; move canonical price WITHOUT rebuilding.
        pricing().upsertPrice(new UpsertPriceCommand("TZP-L002", 7777L, 12002L,
                Currency.INR, null, null, "seed", 1L));
        JsonNode body = ok("/v1/products/TZP-L002?pin=" + PIN);
        assertThat(body.at("/sellingPricePaise").asLong())
                .as("public PDP serves CURRENT canonical price, not the stale projected snapshot")
                .isEqualTo(7777L);
    }

    @Test void unknown_product_is_flat_404() {
        assertError("/v1/products/TZP-99999999", 404, "NOT_FOUND");
    }

    @Test void product_detail_lat_lng_is_400() {
        assertError("/v1/products/TZP-L001?lng=77.6", 400, "INVALID_REQUEST");
    }

    // ---------- serviceability ----------

    @Test void serviceability_covered_pin_returns_version_no_location_leak() {
        JsonNode body = ok("/v1/serviceability?pin=" + PIN);
        assertThat(body.at("/serviceable").asBoolean()).isTrue();
        assertThat(body.at("/serviceAreaId").asText()).isEqualTo("SA-1");
        assertThat(body.at("/serviceAreaVersion").isInt()).as("authoritative version threaded").isTrue();
        assertThat(body.at("/requestId").asText()).startsWith("req_");
        assertThat(body.has("etaMinutesMin")).as("ETA omitted (no source)").isFalse();
        assertNoInternalLeak(body.toString());
    }

    @Test void serviceability_uncovered_pin_is_200_not_serviceable() {
        JsonNode body = ok("/v1/serviceability?pin=560099");
        assertThat(body.at("/serviceable").asBoolean()).isFalse();
        assertThat(body.has("serviceAreaId")).isFalse(); // omitted when null
    }

    @Test void serviceability_missing_pin_is_400() {
        assertError("/v1/serviceability", 400, "INVALID_REQUEST");
    }

    @Test void serviceability_lat_lng_is_400() {
        assertError("/v1/serviceability?lat=12.9&lng=77.6", 400, "INVALID_REQUEST");
    }

    // ---------- cursor cross-surface replay is rejected ----------

    @Test void a_consumer_list_cursor_is_rejected_on_the_commerce_surface() {
        // mint a consumer cursor on /catalog/v1, then present it to /v1 → INVALID_CURSOR
        JsonNode consumer = ok("/catalog/v1/categories/" + C_RICE + "/products?page_size=2");
        String consumerCursor = consumer.at("/next_cursor").asText();
        assertThat(consumerCursor).isNotEmpty();
        assertError("/v1/categories/" + C_RICE + "/products?cursor=" + consumerCursor, 400, "INVALID_CURSOR");
    }

    // ---------- no request-time writes ----------

    @Test void v1_reads_perform_no_writes() {
        List<String> collections = List.of("products", "product_card_base", "price_current",
                "inventory", "media_refs", "service_areas", "work_queue");
        List<List<Document>> before = snapshot(collections);
        ok("/v1/categories");
        ok("/v1/categories/" + C_RICE + "/products?pin=" + PIN);
        ok("/v1/products/TZP-L001?pin=" + PIN);
        ok("/v1/serviceability?pin=" + PIN);
        assertThat(snapshot(collections)).as("public /v1 reads are READ ONLY").isEqualTo(before);
    }

    private List<List<Document>> snapshot(List<String> collections) {
        List<List<Document>> all = new java.util.ArrayList<>();
        for (String c : collections) {
            all.add(db.getCollection(c).find().into(new java.util.ArrayList<>()));
        }
        return all;
    }

    private void assertError(String path, int status, String code) {
        ResponseEntity<JsonNode> res = get(path, JsonNode.class);
        assertThat(res.getStatusCode().value()).as(path + " -> " + res.getBody()).isEqualTo(status);
        assertThat(res.getBody().at("/code").asText()).isEqualTo(code);
        assertThat(res.getBody().at("/requestId").asText()).startsWith("req_");
        assertThat(res.getBody().has("retryable")).isTrue();
    }
}
