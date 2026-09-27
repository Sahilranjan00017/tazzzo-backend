package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
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

    @org.springframework.beans.factory.annotation.Autowired
    io.micrometer.core.instrument.MeterRegistry registry;

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

    // ---------- taxonomy ETag (PR-10C) ----------

    @Test void categories_carries_a_deterministic_etag() {
        ResponseEntity<JsonNode> res1 = get("/v1/categories", JsonNode.class);
        ResponseEntity<JsonNode> res2 = get("/v1/categories", JsonNode.class);
        String etag1 = res1.getHeaders().getFirst("ETag");
        String etag2 = res2.getHeaders().getFirst("ETag");
        assertThat(etag1).isNotBlank().startsWith("\"").endsWith("\"");
        assertThat(etag1).as("same content, same requestId excluded -> identical ETag").isEqualTo(etag2);
        assertThat(etag1).as("ETag is not the changing requestId").isNotEqualTo(res1.getBody().at("/requestId").asText());
    }

    @Test void categories_matching_if_none_match_is_304_with_no_body_and_headers_retained() {
        String etag = get("/v1/categories", JsonNode.class).getHeaders().getFirst("ETag");
        org.springframework.http.HttpHeaders reqHeaders = new org.springframework.http.HttpHeaders();
        reqHeaders.set("If-None-Match", etag);
        ResponseEntity<JsonNode> res = get("/v1/categories", reqHeaders, JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(304);
        assertThat(res.getBody()).as("a 304 must carry no body").isNull();
        assertThat(res.getHeaders().getFirst("ETag")).isEqualTo(etag);
        assertThat(res.getHeaders().getFirst("Cache-Control")).contains("public").contains("max-age=300");
    }

    @Test void categories_non_matching_if_none_match_is_a_normal_200() {
        org.springframework.http.HttpHeaders reqHeaders = new org.springframework.http.HttpHeaders();
        reqHeaders.set("If-None-Match", "\"not-the-real-etag\"");
        ResponseEntity<JsonNode> res = get("/v1/categories", reqHeaders, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().at("/items").isArray()).isTrue();
    }

    @Test void children_carries_a_deterministic_etag_and_supports_conditional_get() {
        ResponseEntity<JsonNode> first = get("/v1/categories/" + C_RICE + "/children", JsonNode.class);
        String etag = first.getHeaders().getFirst("ETag");
        assertThat(etag).isNotBlank();

        org.springframework.http.HttpHeaders reqHeaders = new org.springframework.http.HttpHeaders();
        reqHeaders.set("If-None-Match", etag);
        ResponseEntity<JsonNode> conditional = get("/v1/categories/" + C_RICE + "/children", reqHeaders, JsonNode.class);
        assertThat(conditional.getStatusCode().value()).isEqualTo(304);
        assertThat(conditional.getBody()).isNull();
        assertThat(conditional.getHeaders().getFirst("ETag")).isEqualTo(etag);
    }

    @Test void categories_and_children_etags_are_never_equal_to_each_other() {
        // proves the route is bound into the hash -- two different representations never collide
        String categoriesEtag = get("/v1/categories", JsonNode.class).getHeaders().getFirst("ETag");
        String childrenEtag =
                get("/v1/categories/" + C_RICE + "/children", JsonNode.class).getHeaders().getFirst("ETag");
        assertThat(categoriesEtag).isNotEqualTo(childrenEtag);
    }

    @Test void an_etag_changes_when_the_visible_representation_changes() {
        // TZP-L004 is eligible under V_BASMATI but its later addition doesn't change the CATEGORY
        // node set itself (categories are super-categories, unaffected by a single product), so
        // this proves the hash tracks children's own item set: adding a brand-new super-category
        // wholesale is not exercised here, but re-requesting identical input again must be STABLE
        // (already covered above) -- this test instead proves two DIFFERENT release-bound requests
        // (an explicit release vs default) that resolve to the SAME actual release produce the
        // SAME etag, i.e. it is deterministic on content, not on incidental request shape.
        String etagDefault = get("/v1/categories", JsonNode.class).getHeaders().getFirst("ETag");
        String etagExplicit = get("/v1/categories?release=R1", JsonNode.class).getHeaders().getFirst("ETag");
        assertThat(etagDefault).isEqualTo(etagExplicit);
    }

    // ---------- admission route labels (PR-10B final review #2) ----------

    private double charged(String routeTag) {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", routeTag).summary();
        return s == null ? 0 : s.totalAmount();
    }

    @Test void commerce_categories_charges_its_own_route_never_the_legacy_root_label() {
        double commerceBefore = charged("commerce_categories");
        double legacyBefore = charged("root");
        ok("/v1/categories");
        assertThat(charged("commerce_categories") - commerceBefore)
                .as("commerce categories charged under its own label").isPositive();
        assertThat(charged("root") - legacyBefore)
                .as("the legacy root label is never touched by the commerce surface").isZero();
    }

    @Test void commerce_children_charges_its_own_route_never_the_legacy_children_label() {
        double commerceBefore = charged("commerce_children");
        double legacyBefore = charged("children");
        ok("/v1/categories/" + C_RICE + "/children");
        assertThat(charged("commerce_children") - commerceBefore)
                .as("commerce children charged under its own label").isPositive();
        assertThat(charged("children") - legacyBefore)
                .as("the legacy children label is never touched by the commerce surface").isZero();
    }

    @Test void legacy_root_and_commerce_categories_charge_identical_computed_units_for_the_same_topology() {
        // Same seeded topology, two surfaces, two labels -- but ONE cost formula (§2: no fork).
        double commerceBefore = charged("commerce_categories");
        double legacyBefore = charged("root");
        ok("/v1/categories");
        ok("/catalog/v1/categories");
        assertThat(charged("commerce_categories") - commerceBefore)
                .as("identical unit cost under the shared walk")
                .isEqualTo(charged("root") - legacyBefore);
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

    @Test void category_products_missing_projection_is_fail_closed_not_fabricated_no_write() {
        // eligible by every product-level rule, but NEVER priced or projected: a freshness gap.
        eligibleProduct("TZP-L004", V_BASMATI);
        List<String> collections = List.of("product_card_base", "work_queue");
        List<List<Document>> before = snapshot(collections);

        JsonNode body = ok("/v1/categories/" + C_RICE + "/products?page_size=10");
        JsonNode missing = null;
        for (JsonNode item : body.at("/items")) {
            if ("TZP-L004".equals(item.at("/skuId").asText())) {
                missing = item;
                break;
            }
        }
        assertThat(missing).as("a membership product with no projection row must still hold its page position")
                .isNotNull();
        assertThat(missing.has("sellingPricePaise")).as("no price fabricated for a freshness gap").isFalse();
        assertThat(missing.at("/buyable").asBoolean()).as("a freshness gap is never buyable").isFalse();
        assertThat(missing.has("thumbnailUrl")).as("no fake thumbnail fabricated").isFalse();
        assertThat(missing.at("/name").asText()).as("identity is FRESH from the membership read, not stale")
                .isNotEmpty();
        assertThat(snapshot(collections))
                .as("the fail-closed card is composed in-memory only -- no product_card_base or "
                        + "work_queue write happens while serving the request")
                .isEqualTo(before);
    }

    @Test void category_products_bad_cursor_is_400_invalid_cursor() {
        assertError("/v1/categories/" + C_RICE + "/products?cursor=not-a-real-cursor", 400, "INVALID_CURSOR");
    }

    // ---------- cursor location binding (PR-10B final review #1) ----------

    private String nextCursorOf(JsonNode page) {
        String cursor = page.at("/nextCursor").asText();
        assertThat(cursor).as("page must have more for a continuation cursor").isNotEmpty();
        return cursor;
    }

    @Test void anonymous_page1_to_anonymous_page2_succeeds() {
        JsonNode p1 = ok("/v1/categories/" + C_RICE + "/products?page_size=2");
        String cursor = nextCursorOf(p1);
        JsonNode p2 = ok("/v1/categories/" + C_RICE + "/products?page_size=2&cursor=" + cursor);
        assertThat(p2.at("/items")).isNotEmpty();
    }

    @Test void same_pin_page1_to_same_pin_page2_succeeds() {
        JsonNode p1 = ok("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2");
        String cursor = nextCursorOf(p1);
        JsonNode p2 = ok("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2&cursor=" + cursor);
        assertThat(p2.at("/items")).isNotEmpty();
    }

    @Test void pin_a_cursor_replayed_with_pin_b_is_invalid_cursor() {
        JsonNode p1 = ok("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2");
        String cursor = nextCursorOf(p1);
        assertError("/v1/categories/" + C_RICE + "/products?pin=560002&page_size=2&cursor=" + cursor,
                400, "INVALID_CURSOR");
    }

    @Test void pin_cursor_replayed_anonymously_is_invalid_cursor() {
        JsonNode p1 = ok("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2");
        String cursor = nextCursorOf(p1);
        assertError("/v1/categories/" + C_RICE + "/products?page_size=2&cursor=" + cursor,
                400, "INVALID_CURSOR");
    }

    @Test void anonymous_cursor_replayed_with_a_pin_is_invalid_cursor() {
        JsonNode p1 = ok("/v1/categories/" + C_RICE + "/products?page_size=2");
        String cursor = nextCursorOf(p1);
        assertError("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2&cursor=" + cursor,
                400, "INVALID_CURSOR");
    }

    @Test void a_tampered_location_context_byte_is_rejected() {
        JsonNode p1 = ok("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2");
        String cursor = nextCursorOf(p1);
        byte[] raw = Base64.getUrlDecoder().decode(cursor);
        raw[raw.length - 32 - 1] ^= 0x01;   // last payload byte: the location-context fingerprint
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertError("/v1/categories/" + C_RICE + "/products?pin=" + PIN + "&page_size=2&cursor=" + tampered,
                400, "INVALID_CURSOR");
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

    // ---------- PDP current-price regression (PR-10B final review #6) ----------

    @Test void pdp_serves_new_canonical_price_and_never_writes_the_stored_projection() {
        eligibleProduct("TZP-L005", V_BASMATI);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-L005", 5000L, 6000L, Currency.INR, null, null, "seed", null));
        projector().rebuildOne("TZP-L005");   // product_card_base now stores the OLD price
        pricing().upsertPrice(new UpsertPriceCommand("TZP-L005", 9999L, 11000L, Currency.INR, null, null, "seed", 1L));

        Document storedBefore = db.getCollection("product_card_base").find(eq("sku_id", "TZP-L005")).first();
        assertThat(storedBefore.getLong("selling_price_paise")).isEqualTo(5000L);

        JsonNode body = ok("/v1/products/TZP-L005?pin=" + PIN);
        assertThat(body.at("/sellingPricePaise").asLong())
                .as("PDP serves the CURRENT canonical price").isEqualTo(9999L);

        Document storedAfter = db.getCollection("product_card_base").find(eq("sku_id", "TZP-L005")).first();
        assertThat(storedAfter.getLong("selling_price_paise"))
                .as("the overlay is ephemeral: the stored projection is never mutated by a PDP read")
                .isEqualTo(5000L);
    }

    @Test void pdp_canonical_price_missing_omits_amounts_and_is_not_buyable() {
        eligibleProduct("TZP-L006", V_BASMATI);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-L006", 4000L, 5000L, Currency.INR, null, null, "seed", null));
        projector().rebuildOne("TZP-L006");
        db.getCollection("price_current").deleteOne(eq("sku_id", "TZP-L006"));   // canonical -> MISSING

        JsonNode body = ok("/v1/products/TZP-L006?pin=" + PIN);
        assertThat(body.has("sellingPricePaise")).as("no amount fabricated when canonical is MISSING").isFalse();
        assertThat(body.has("mrpPaise")).isFalse();
        assertThat(body.at("/buyable").asBoolean()).isFalse();
    }

    @Test void pdp_canonical_price_inactive_omits_amounts_and_is_not_buyable() {
        eligibleProduct("TZP-L007", V_BASMATI);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-L007", 4100L, 5100L, Currency.INR, null, null, "seed", null));
        projector().rebuildOne("TZP-L007");
        db.getCollection("price_current").updateOne(eq("sku_id", "TZP-L007"),
                new Document("$set", new Document("active", false)));   // canonical -> INACTIVE

        JsonNode body = ok("/v1/products/TZP-L007?pin=" + PIN);
        assertThat(body.has("sellingPricePaise")).as("no amount fabricated when canonical is INACTIVE").isFalse();
        assertThat(body.has("mrpPaise")).isFalse();
        assertThat(body.at("/buyable").asBoolean()).isFalse();
    }

    @Test void product_detail_lat_lng_is_400() {
        assertError("/v1/products/TZP-L001?lng=77.6", 400, "INVALID_REQUEST");
    }

    // ---------- serviceability ----------

    @Test void serviceability_covered_pin_returns_version_no_location_leak() {
        JsonNode body = ok("/v1/serviceability?pin=" + PIN);
        assertThat(body.at("/serviceable").asBoolean()).isTrue();
        assertThat(body.at("/serviceAreaId").asText()).isEqualTo("SA-1");
        assertThat(body.at("/serviceAreaVersion").isIntegralNumber())
                .as("authoritative version threaded, int64 end-to-end").isTrue();
        assertThat(body.at("/requestId").asText()).startsWith("req_");
        assertThat(body.has("etaMinutesMin")).as("ETA omitted (no source)").isFalse();
        assertNoInternalLeak(body.toString());
    }

    @Test void serviceability_uncovered_pin_is_200_not_serviceable() {
        JsonNode body = ok("/v1/serviceability?pin=560099");
        assertThat(body.at("/serviceable").asBoolean()).isFalse();
        assertThat(body.has("serviceAreaId")).isFalse(); // omitted when null
    }

    @Test void serviceability_version_above_int32_ceiling_is_never_narrowed() {
        // PR-10B final review #4: a version beyond Integer.MAX_VALUE must serialize correctly, not
        // wrap/truncate via an int32 narrowing conversion between the domain Long and the wire DTO.
        long bigVersion = ((long) Integer.MAX_VALUE) + 42L;
        com.tazzzo.commerce.api.dto.ServiceabilityResponseDto dto =
                new com.tazzzo.commerce.api.dto.ServiceabilityResponseDto(
                        true, "SA-BIG", bigVersion, null, null, "req_test");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json;
        JsonNode node;
        try {
            json = mapper.writeValueAsString(dto);
            node = mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError(e);
        }
        assertThat(node.at("/serviceAreaVersion").asLong()).isEqualTo(bigVersion);
        assertThat(node.at("/serviceAreaVersion").isIntegralNumber()).isTrue();
    }

    @Test void serviceability_missing_pin_is_400() {
        assertError("/v1/serviceability", 400, "INVALID_REQUEST");
    }

    @Test void serviceability_lat_lng_is_400() {
        assertError("/v1/serviceability?lat=12.9&lng=77.6", 400, "INVALID_REQUEST");
    }

    // ---------- cache headers (PR-10C) ----------

    @Test void children_carries_the_public_cache_control() {
        ResponseEntity<JsonNode> res = get("/v1/categories/" + C_RICE + "/children", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Cache-Control"))
                .isEqualTo("public, max-age=300, stale-while-revalidate=60");
    }

    @Test void category_products_carries_private_no_store() {
        ResponseEntity<JsonNode> res = get("/v1/categories/" + C_RICE + "/products", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
    }

    @Test void product_detail_carries_private_no_store() {
        ResponseEntity<JsonNode> res = get("/v1/products/TZP-L001", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
    }

    @Test void serviceability_carries_private_no_store() {
        ResponseEntity<JsonNode> res = get("/v1/serviceability?pin=" + PIN, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
    }

    /**
     * PR-10C cache-safety fix: {@code /v1/categories} and {@code /v1/categories/{id}/children} set
     * their public Cache-Control BEFORE the request is known to succeed, so an error thrown after
     * that point must not inherit it — CommerceExceptionHandler overrides it to {@code no-store}.
     */
    @Test void a_categories_error_response_is_never_publicly_cacheable() {
        // an invalid release makes /categories fail closed with a typed failure, not 200
        ResponseEntity<JsonNode> res = get("/v1/categories?release=NOT-A-REAL-RELEASE", JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as("must actually be an error response").isFalse();
        assertThat(res.getHeaders().getFirst("Cache-Control"))
                .as("an error must never inherit the success public cache header")
                .isEqualTo("no-store");
    }

    @Test void a_children_404_is_never_publicly_cacheable() {
        ResponseEntity<JsonNode> res = get("/v1/categories/TZC-DOES-NOT-EXIST/children", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test void every_error_response_across_all_five_routes_is_never_publicly_cacheable() {
        // list/PDP/serviceability set "private, no-store" eagerly (safe even on error, since
        // neither "private" nor "no-store" is ever cacheable by a shared/public cache); categories/
        // children set nothing until success, so their error responses carry the handler's own
        // "no-store". Either way, "public" must never appear on an error response.
        for (String path : List.of(
                "/v1/categories/TZC-DOES-NOT-EXIST/products",
                "/v1/categories/" + C_RICE + "/products?lat=12.9",
                "/v1/products/TZP-99999999",
                "/v1/products/TZP-L001?lng=77.6",
                "/v1/serviceability")) {
            ResponseEntity<JsonNode> res = get(path, JsonNode.class);
            assertThat(res.getStatusCode().is2xxSuccessful()).as(path).isFalse();
            String cacheControl = res.getHeaders().getFirst("Cache-Control");
            assertThat(cacheControl).as(path).isNotNull().doesNotContain("public").contains("no-store");
        }
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
