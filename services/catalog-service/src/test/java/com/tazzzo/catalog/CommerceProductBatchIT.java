package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.CommerceProductBatchService;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.common.money.Currency;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.media.MediaService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import com.tazzzo.common.audit.DomainAudit;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/products:batch} over real HTTP, Mongo and Redis. The contract under test: for every id it answers
 * exactly what {@code GET /v1/products/{id}} answers (same card, same visibility), in request order, in a fixed number
 * of indexed reads, with one fixed 400 for any malformed request and no way to tell an unknown id from a non-public one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceProductBatchIT extends AbstractConsumerIT {

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "commerce-cursor-fixture-key-32b!!".getBytes(StandardCharsets.UTF_8));
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String V_BASMATI = "TZV-000001";
    private static final String V_SALT = "TZV-000057";
    private static final String G_SALT = "TZG-000013";
    private static final String PIN = "560001";
    private static final String PIN_UNCOVERED = "560099";
    private static final String ROUTE = "commerce_products_batch";
    /** Detail-only keys of the single-id answer; everything else is the card the batch must equal. */
    private static final Set<String> DETAIL_ONLY = Set.of("gallery", "attributes", "description", "highlights",
            "variants", "legal", "serviceability", "resolvedReleaseId", "requestId");

    @Autowired com.mongodb.client.MongoClient client;
    @Autowired MeterRegistry registry;
    @Autowired CommerceProductBatchService service;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_commerce_product_batch_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> FIXTURE_KEY_B64);
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.tazzzo.com");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "1000000");
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

    /** An eligible, priced, projected product. */
    private void sellable(String sku, long price) {
        eligibleProduct(sku, V_BASMATI);
        pricing().upsertPrice(new UpsertPriceCommand(sku, price, price + 2000, Currency.INR, null, null, "seed", null));
        projector().rebuildOne(sku);
    }

    private static String bulkId(int i) {
        return String.format("TZP-B%03d", i);
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        for (int i = 1; i <= 3; i++) {
            sellable(String.format("TZP-L%03d", i), 10000L + i);
        }
        for (int i = 1; i <= 50; i++) {
            sellable(bulkId(i), 20000L + i);
        }
        serviceability().upsertServiceArea(new UpsertServiceAreaCommand(
                PIN, "SA-1", List.of(new ServiceabilityRoute("FL-1", 0, true)), "seed", null));
        inventory().setInventory(new SetInventoryCommand("TZP-L001", "FL-1", 50, 2, 10, "seed", null));
        inventory().setInventory(new SetInventoryCommand("TZP-L002", "FL-1", 1, 0, 0, "seed", null));

        // Everything a customer must never be told about, one per reason.
        product("TZP-X-DRAFT", V_BASMATI, "draft", "confirmed", "single");
        product("TZP-X-DISC", V_BASMATI, "discontinued", "confirmed", "single");
        product("TZP-X-ARCH", V_BASMATI, "archived", "confirmed", "single");
        product("TZP-X-MERGING", V_BASMATI, "merging", "confirmed", "single");
        product("TZP-X-PROV", V_BASMATI, "active", "provisional", "single");
        product("TZP-X-BUNDLE", V_BASMATI, "active", "confirmed", "bundle");
        product("TZP-X-HOLD", "UNCLASSIFIED", "active", "confirmed", "single");
        product("TZP-X-MERGED", V_BASMATI, "merged", "confirmed", "single");
        db.getCollection("products").updateOne(eq("_id", "TZP-X-MERGED"),
                new Document("$set", new Document("merged_into", "TZP-L003")));
        eligibleProduct("TZP-X-SALT", V_SALT);   // eligible, but its branch can be hidden per test
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    // ---------- helpers ----------

    private ResponseEntity<JsonNode> res(String target) {
        return res(target, new HttpHeaders());
    }

    private ResponseEntity<JsonNode> res(String target, HttpHeaders headers) {
        // a URI object is sent as written: no template expansion, no re-encoding of %0A and friends
        return rest.exchange(URI.create(url(target)), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private JsonNode ok(String target) {
        ResponseEntity<JsonNode> r = res(target);
        assertThat(r.getStatusCode().value()).as(target + " -> " + r.getBody()).isEqualTo(200);
        return r.getBody();
    }

    private static String batch(String... ids) {
        return "/v1/products:batch?ids=" + String.join(",", ids);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> itemIds(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("items").forEach(n -> out.add(n.get("skuId").asText()));
        return out;
    }

    private JsonNode singleCard(String id, String query) {
        ObjectNode card = ((ObjectNode) ok("/v1/products/" + id + query)).deepCopy();
        DETAIL_ONLY.forEach(card::remove);
        return card;
    }

    private double cost() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", ROUTE).summary();
        return s == null ? 0 : s.totalAmount();
    }

    private double outcome(String outcome) {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", ROUTE, "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    /** The one fixed 400: flat envelope, fixed words, no echo, never cacheable, nothing charged, nothing read. */
    private void assertRefused(String target, String... mustNotAppear) {
        double before = cost();
        ResponseEntity<JsonNode> r = res(target);
        assertThat(r.getStatusCode().value()).as(target + " -> " + r.getBody()).isEqualTo(400);
        JsonNode b = r.getBody();
        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder("code", "message", "requestId", "retryable");
        assertThat(b.at("/code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(b.at("/message").asText()).isEqualTo("invalid request");
        assertThat(b.at("/requestId").asText()).startsWith("req_");
        assertThat(b.at("/retryable").asBoolean()).isFalse();
        assertThat(r.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(r.getHeaders().getFirst("ETag")).isNull();
        for (String s : mustNotAppear) {
            assertThat(b.toString()).as("no echo of the input").doesNotContain(s);
        }
        assertThat(cost() - before).as(target + ": a malformed request is refused before admission").isZero();
        assertThat(PRODUCT_FINDS.get()).as("and before any read").isZero();
    }

    // ---------- the answer ----------

    @Test
    void each_item_is_exactly_the_card_the_single_id_read_answers_anonymous_and_for_a_serviceable_pin() {
        for (String query : List.of("", "?pin=" + PIN, "?pin=" + PIN_UNCOVERED)) {
            String sep = query.isEmpty() ? "?" : "&";
            JsonNode body = ok("/v1/products:batch" + query + sep + "ids=TZP-L001,TZP-L002,TZP-L003");
            assertThat(itemIds(body)).as(query).containsExactly("TZP-L001", "TZP-L002", "TZP-L003");
            for (JsonNode item : body.get("items")) {
                assertThat(item).as(query + " " + item.get("skuId").asText())
                        .isEqualTo(singleCard(item.get("skuId").asText(), query));
            }
        }
        JsonNode located = ok("/v1/products:batch?pin=" + PIN + "&ids=TZP-L001,TZP-L002");
        assertThat(located.at("/items/0/buyable").asBoolean()).as("stocked at a covered PIN").isTrue();
        assertThat(located.at("/items/0/serviceable").asBoolean()).isTrue();
    }

    @Test
    void the_envelope_is_release_items_missing_and_request_id_only_and_never_cached() {
        ResponseEntity<JsonNode> r = res(batch("TZP-L001", "TZP-NOPE"));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        JsonNode b = r.getBody();
        assertThat(b.fieldNames()).toIterable().containsExactlyInAnyOrder("resolvedReleaseId", "items", "missing", "requestId");
        assertThat(b.at("/resolvedReleaseId").asText()).isEqualTo("R1");
        assertThat(b.at("/requestId").asText()).startsWith("req_");
        assertThat(r.getHeaders().getFirst("X-Request-Id")).isEqualTo(b.at("/requestId").asText());
        assertThat(r.getHeaders().getFirst("Cache-Control")).as("same policy as the single-id read").isEqualTo("private, no-store");
        assertThat(r.getHeaders().getFirst("ETag")).isNull();
        String json = b.toString().toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("fulfillment", "onhand", "reserved", "assetkey", "internal_key", "lifecycle", "merged")) {
            assertThat(json).doesNotContain(forbidden);
        }
        assertThat(texts(b.get("missing"))).containsExactly("TZP-NOPE");
    }

    @Test
    void the_answer_follows_request_order_not_id_or_insertion_order() {
        assertThat(itemIds(ok(batch("TZP-L003", "TZP-L001", "TZP-L002")))).containsExactly("TZP-L003", "TZP-L001", "TZP-L002");
        assertThat(itemIds(ok(batch("TZP-L002", "TZP-L003", "TZP-L001")))).containsExactly("TZP-L002", "TZP-L003", "TZP-L001");
        JsonNode mixed = ok(batch("TZP-N2", "TZP-L003", "TZP-N1", "TZP-L001"));
        assertThat(itemIds(mixed)).containsExactly("TZP-L003", "TZP-L001");
        assertThat(texts(mixed.get("missing"))).containsExactly("TZP-N2", "TZP-N1");
    }

    @Test
    void duplicates_collapse_to_the_first_occurrence_and_are_charged_once() {
        double before = cost();
        JsonNode body = ok(batch("TZP-L002", "TZP-L001", "TZP-L002", "TZP-N1", "TZP-L001", "TZP-N1"));
        assertThat(itemIds(body)).containsExactly("TZP-L002", "TZP-L001");
        assertThat(texts(body.get("missing"))).containsExactly("TZP-N1");
        assertThat(cost() - before).as("1 + distinct ids").isEqualTo(1 + 3);
    }

    @Test
    void ids_are_exact_and_case_is_never_folded() {
        JsonNode body = ok(batch("TZP-l001", "TZP-L001"));
        assertThat(itemIds(body)).containsExactly("TZP-L001");
        assertThat(texts(body.get("missing"))).containsExactly("TZP-l001");
    }

    @Test
    void fifty_ids_are_served_in_full_and_match_the_single_id_read_one_for_one() {
        String[] ids = IntStream.rangeClosed(1, 50).mapToObj(CommerceProductBatchIT::bulkId).toArray(String[]::new);
        double before = cost();
        ResponseEntity<JsonNode> r = res(batch(ids) + "&pin=" + PIN);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(itemIds(r.getBody())).containsExactly(ids);
        assertThat(r.getBody().get("missing")).isEmpty();
        assertThat(cost() - before).isEqualTo(51);
        for (int i : new int[]{0, 24, 49}) {
            assertThat(r.getBody().get("items").get(i)).isEqualTo(singleCard(ids[i], "?pin=" + PIN));
        }
        assertThat(r.getBody().toString().getBytes(StandardCharsets.UTF_8).length)
                .as("response size is bounded by the cap").isLessThan(50 * 1024);
    }

    @Test
    void fifty_one_ids_are_refused_even_when_they_are_duplicates_or_unknown() {
        List<String> distinct = IntStream.rangeClosed(1, 51).mapToObj(CommerceProductBatchIT::bulkId).toList();
        assertRefused("/v1/products:batch?ids=" + String.join(",", distinct), "TZP-B001");
        List<String> dup = new ArrayList<>(java.util.Collections.nCopies(51, "TZP-L001"));
        assertRefused("/v1/products:batch?ids=" + String.join(",", dup), "TZP-L001");
        assertThat(service.maxIds()).isEqualTo(50);
    }

    // ---------- refusals ----------

    @Test
    void every_malformed_ids_value_is_the_same_fixed_400_without_an_echo() {
        String id = "TZP-L001";
        String tail41 = "TZP-" + "A".repeat(41);
        assertRefused("/v1/products:batch", "ids");                                    // absent
        assertRefused("/v1/products:batch?ids=");                                      // empty
        assertRefused("/v1/products:batch?ids=tzp-1", "tzp-1");                        // lowercase prefix
        assertRefused("/v1/products:batch?ids=" + id + ",tzp-L002", "tzp-L002");       // one bad poisons all
        assertRefused("/v1/products:batch?ids=" + tail41, tail41);                     // 41-char tail
        assertRefused("/v1/products:batch?ids=" + id + ",,TZP-L002");                  // empty element
        assertRefused("/v1/products:batch?ids=" + id + ",");                           // trailing comma
        assertRefused("/v1/products:batch?ids=," + id);                                // leading comma
        assertRefused("/v1/products:batch?ids=" + id + "%0A");                         // trailing newline
        assertRefused("/v1/products:batch?ids=" + id + "%0A,TZP-L002");
        assertRefused("/v1/products:batch?ids=" + id + "%0D%0AX-Injected:1");
        assertRefused("/v1/products:batch?ids=" + id + ",%20TZP-L002");                // whitespace, no trimming
        assertRefused("/v1/products:batch?ids=%20" + id);
        assertRefused("/v1/products:batch?ids=TZP-L00%C3%A9", "TZP-L00");              // unicode
        assertRefused("/v1/products:batch?ids=TZP-%EF%BC%91");                         // fullwidth digit
        assertRefused("/v1/products:batch?ids=TZP-L001%00");                           // NUL
        assertRefused("/v1/products:batch?ids=TZP-");                                  // no tail
        assertRefused("/v1/products:batch?ids=TZP_L001");
        assertRefused("/v1/products:batch?ids=TZP-L001;TZP-L002");
        assertRefused("/v1/products:batch?ids=%7B%22$ne%22:1%7D");                     // operator-looking junk
        assertRefused("/v1/products:batch?ids=" + id + "&ids=TZP-L002");               // repeated parameter
        assertRefused("/v1/products:batch?ids=" + id + "&ids=");
        assertRefused("/v1/products:batch?ids=TZP-L001%2CTZP-L002%2C");                // encoded trailing comma
    }

    @Test
    void query_string_and_ids_value_are_bounded_before_anything_is_parsed() {
        int max = service.maxQueryLength();
        assertThat(max).isLessThan(4096);
        assertRefused("/v1/products:batch?ids=TZP-L001&release=" + "R".repeat(max));
        String huge = "TZP-1,".repeat(900);   // over our bound, under Tomcat's own 16 KB request-head limit
        assertRefused("/v1/products:batch?ids=" + huge);
        // the longest legal request: 50 maximal ids
        String maximal = IntStream.range(0, 50).mapToObj(i -> String.format("TZP-%s%02d", "A".repeat(38), i))
                .collect(Collectors.joining(","));
        assertThat(maximal.length()).isLessThanOrEqualTo(service.maxIdsParamLength());
        assertThat(res("/v1/products:batch?ids=" + maximal).getStatusCode().value()).isEqualTo(200);
        assertThat(res("/v1/products:batch?ids=" + maximal.replace(",", "%2C")).getStatusCode().value())
                .as("percent-encoded separators still fit the bound").isEqualTo(200);
    }

    @Test
    void location_parameters_are_validated_exactly_like_the_single_id_read() {
        for (String bad : List.of("pin=12", "pin=abcdef", "lat=12.9&lng=77.6", "lng=77.6", "lat=12.9",
                "pin=" + PIN + "&lat=12.9&lng=77.6")) {
            int single = res("/v1/products/TZP-L001?" + bad).getStatusCode().value();
            ResponseEntity<JsonNode> r = res("/v1/products:batch?ids=TZP-L001&" + bad);
            assertThat(single).as(bad).isEqualTo(400);
            assertThat(r.getStatusCode().value()).as(bad).isEqualTo(400);
            assertThat(r.getBody().at("/code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(res("/v1/products:batch?ids=TZP-L001&release=NOT-A-REAL-RELEASE").getStatusCode().value())
                .isEqualTo(res("/v1/products/TZP-L001?release=NOT-A-REAL-RELEASE").getStatusCode().value());
        assertThat(ok("/v1/products:batch?ids=TZP-L001&release=R1").at("/resolvedReleaseId").asText()).isEqualTo("R1");
    }

    @Test
    void a_query_string_the_container_cannot_decode_is_the_fixed_400_from_the_malformed_query_filter() throws IOException {
        for (String target : List.of("/v1/products:batch?ids=TZP-L001&x=%ZZ", "/v1/products:batch?ids=%ZZ",
                "/v1/products:batch?ids=TZP-L001&x=%C3%28")) {
            String[] r = rawGet(target);
            assertThat(r[0]).as(target).isEqualTo("400");
            JsonNode b = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r[1]);
            assertThat(b.at("/code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(b.at("/message").asText()).isEqualTo("invalid request");
            assertThat(b.has("requestId") ? b.at("/requestId").asText() : b.at("/request_id").asText()).startsWith("req_");
            String[] single = rawGet(target.replace("/v1/products:batch", "/v1/products/TZP-L001"));
            assertThat(r[0]).isEqualTo(single[0]);
            assertThat(b.at("/message").asText()).isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(single[1]).at("/message").asText());
            assertThat(r[1]).doesNotContain("ZZ").doesNotContain("Exception").doesNotContain("\tat ");
        }
        assertThat(PRODUCT_FINDS.get()).isZero();
    }

    private String[] rawGet(String target) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(15000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            String all = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String body = all.substring(all.indexOf("\r\n\r\n") + 4);
            return new String[]{all.substring(9, 12), body.substring(body.indexOf('{'), body.lastIndexOf('}') + 1)};
        }
    }

    // ---------- visibility: no oracle ----------

    private static final List<String> NOT_PUBLIC = List.of("TZP-X-DRAFT", "TZP-X-DISC", "TZP-X-ARCH", "TZP-X-MERGING",
            "TZP-X-PROV", "TZP-X-BUNDLE", "TZP-X-HOLD", "TZP-X-MERGED");

    @Test
    void a_non_public_product_is_never_returned_and_looks_exactly_like_an_unknown_id() {
        for (String hidden : NOT_PUBLIC) {
            JsonNode asHidden = ok(batch("TZP-L001", hidden));
            JsonNode asUnknown = ok(batch("TZP-L001", "TZP-UNKNOWN"));
            assertThat(itemIds(asHidden)).as(hidden).containsExactly("TZP-L001");
            assertThat(texts(asHidden.get("missing"))).as(hidden).containsExactly(hidden);
            assertThat(strip(asHidden).replace(hidden, "X")).as(hidden + " vs unknown")
                    .isEqualTo(strip(asUnknown).replace("TZP-UNKNOWN", "X"));
            assertThat(asHidden.toString()).doesNotContain("draft").doesNotContain("discontinued");
        }
        JsonNode all = ok(batch(NOT_PUBLIC.toArray(String[]::new)));
        assertThat(all.get("items")).isEmpty();
        assertThat(texts(all.get("missing"))).containsExactlyElementsOf(NOT_PUBLIC);
    }

    @Test
    void ineligible_and_unknown_cost_the_same_reads_and_the_same_charge() {
        double c0 = cost();
        ok(batch("TZP-X-DRAFT", "TZP-X-DISC", "TZP-X-HOLD"));
        double hiddenCost = cost() - c0;
        int hiddenProducts = PRODUCT_FINDS.get();
        int hiddenBases = finds("product_card_base");
        resetCounters();
        c0 = cost();
        ok(batch("TZP-U1", "TZP-U2", "TZP-U3"));
        assertThat(cost() - c0).isEqualTo(hiddenCost);
        assertThat(PRODUCT_FINDS.get()).isEqualTo(hiddenProducts).isEqualTo(1);
        assertThat(finds("product_card_base")).isEqualTo(hiddenBases).isZero();
    }

    @Test
    void an_unreachable_branch_is_missing_exactly_as_the_single_id_read_calls_it_404() {
        assertThat(itemIds(ok(batch("TZP-X-SALT")))).as("baseline: visible").containsExactly("TZP-X-SALT");
        db.getCollection("taxonomy_snapshot_nodes").updateOne(and(eq("release_id", "R1"), eq("node_id", G_SALT)),
                new Document("$set", new Document("status", "deprecated")));
        try {
            assertThat(res("/v1/products/TZP-X-SALT").getStatusCode().value()).isEqualTo(404);
            JsonNode body = ok(batch("TZP-L001", "TZP-X-SALT"));
            assertThat(itemIds(body)).containsExactly("TZP-L001");
            assertThat(texts(body.get("missing"))).containsExactly("TZP-X-SALT");
        } finally {
            db.getCollection("taxonomy_snapshot_nodes").updateOne(and(eq("release_id", "R1"), eq("node_id", G_SALT)),
                    new Document("$set", new Document("status", "active")));
        }
    }

    @Test
    void for_every_fixture_the_batch_agrees_with_the_single_id_read() {
        List<String> all = new ArrayList<>(List.of("TZP-L001", "TZP-L002", "TZP-L003", "TZP-X-SALT", "TZP-NOPE", "TZP-l001"));
        all.addAll(NOT_PUBLIC);
        for (String query : List.of("", "?pin=" + PIN)) {
            JsonNode body = ok("/v1/products:batch" + (query.isEmpty() ? "?" : query + "&") + "ids=" + String.join(",", all));
            Map<String, JsonNode> items = new LinkedHashMap<>();
            body.get("items").forEach(n -> items.put(n.get("skuId").asText(), n));
            for (String id : all) {
                ResponseEntity<JsonNode> single = res("/v1/products/" + id + query);
                if ("TZP-X-MERGED".equals(id)) {
                    // the single read follows a merge to its survivor; the batch never redirects (documented)
                    assertThat(single.getStatusCode().value()).isEqualTo(200);
                    assertThat(items).doesNotContainKey(id);
                    continue;
                }
                if (single.getStatusCode().value() == 200) {
                    assertThat(items).as(id + query).containsKey(id);
                    assertThat(items.get(id)).as(id + query).isEqualTo(singleCard(id, query));
                } else {
                    assertThat(single.getStatusCode().value()).as(id).isEqualTo(404);
                    assertThat(items).as(id + query).doesNotContainKey(id);
                    assertThat(texts(body.get("missing"))).contains(id);
                }
            }
        }
    }

    @Test
    void a_stale_projection_and_a_missing_projection_are_served_exactly_as_the_single_id_read_serves_them() {
        sellable("TZP-S-STALE", 5000);
        db.getCollection("products").updateOne(eq("_id", "TZP-S-STALE"), new Document("$inc", new Document("version", 1)));
        eligibleProduct("TZP-S-NOBASE", V_BASMATI);
        sellable("TZP-S-PRICE", 6000);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-S-PRICE", 4242, 7000, Currency.INR, null, null, "seed", 1L));
        JsonNode body = ok(batch("TZP-S-STALE", "TZP-S-NOBASE", "TZP-S-PRICE") + "&pin=" + PIN);
        assertThat(itemIds(body)).containsExactly("TZP-S-STALE", "TZP-S-NOBASE", "TZP-S-PRICE");
        for (JsonNode item : body.get("items")) {
            assertThat(item).isEqualTo(singleCard(item.get("skuId").asText(), "?pin=" + PIN));
        }
        assertThat(body.at("/items/0/buyable").asBoolean()).as("stale base fails closed").isFalse();
        assertThat(body.at("/items/1/buyable").asBoolean()).as("no base fails closed").isFalse();
        assertThat(body.at("/items/2/sellingPricePaise").asLong()).as("current canonical price").isEqualTo(4242);
    }

    // ---------- public surface: no credential ----------

    @Test
    void no_credential_is_required_and_a_bogus_one_changes_nothing_exactly_like_the_single_id_read() {
        HttpHeaders bogus = new HttpHeaders();
        bogus.setBearerAuth("not-a-token");
        bogus.set("X-Tazzzo-Caller", "storefront");
        bogus.set("X-Tazzzo-Caller-Secret", "x".repeat(40));
        assertThat(res("/v1/products/TZP-L001", bogus).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<JsonNode> r = res(batch("TZP-L001"), bogus);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(itemIds(r.getBody())).containsExactly("TZP-L001");
        // only the exact path is public: nothing under it, no other method
        assertThat(res("/v1/products:batch/x?ids=TZP-L001").getStatusCode().value()).isIn(401, 403, 404);
        ResponseEntity<JsonNode> post = rest.exchange(URI.create(url(batch("TZP-L001"))), HttpMethod.POST,
                new HttpEntity<>("{}", new HttpHeaders()), JsonNode.class);
        ResponseEntity<JsonNode> singlePost = rest.exchange(URI.create(url("/v1/products/TZP-L001")), HttpMethod.POST,
                new HttpEntity<>("{}", new HttpHeaders()), JsonNode.class);
        assertThat(post.getStatusCode()).as("no write verb: answered as the single-id route answers it")
                .isEqualTo(singlePost.getStatusCode());
        assertThat(post.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void a_served_batch_counts_as_a_success_and_a_refused_one_as_invalid_request() {
        double ok0 = outcome("success");
        double bad0 = outcome("invalid_request");
        ok(batch("TZP-L001"));
        res("/v1/products:batch?ids=nope");
        assertThat(outcome("success") - ok0).isEqualTo(1);
        assertThat(outcome("invalid_request") - bad0).isEqualTo(1);
    }

    // ---------- indexed lookup, fixed read count ----------

    @Test
    void the_lookup_is_an_id_index_scan_never_a_collection_scan() {
        List<String> ids = IntStream.rangeClosed(1, 50).mapToObj(CommerceProductBatchIT::bulkId).toList();
        Document filter = Document.parse(CommerceProductBatchService.lookupFilter(ids)
                .toBsonDocument(org.bson.BsonDocument.class, db.getCodecRegistry()).toJson());
        Document explain = db.runCommand(new Document("explain",
                new Document("find", "products").append("filter", filter)).append("verbosity", "queryPlanner"));
        String plan = explain.get("queryPlanner", Document.class).get("winningPlan").toString();
        assertThat(plan).contains("IXSCAN").contains("_id_").doesNotContain("COLLSCAN");
    }

    @Test
    void the_server_profile_of_a_full_batch_shows_only_index_plans_and_a_fixed_number_of_reads() {
        db.runCommand(new Document("profile", 0));
        db.getCollection("system.profile").drop();
        db.runCommand(new Document("profile", 2));
        try {
            resetCounters();
            ok(batch("TZP-L001", "TZP-L002", "TZP-NOPE") + "&pin=" + PIN);
            int smallFinds = FINDS_BY_COLLECTION.values().stream().mapToInt(a -> a.get()).sum();
            Map<String, Integer> small = snapshotFinds();
            resetCounters();
            String[] ids = IntStream.rangeClosed(1, 50).mapToObj(CommerceProductBatchIT::bulkId).toArray(String[]::new);
            ok(batch(ids) + "&pin=" + PIN);
            int bigFinds = FINDS_BY_COLLECTION.values().stream().mapToInt(a -> a.get()).sum();
            assertThat(bigFinds).as("reads do not grow with the number of ids: " + snapshotFinds() + " vs " + small)
                    .isEqualTo(smallFinds);
            assertThat(PRODUCT_FINDS.get()).isEqualTo(1);
            assertThat(finds("product_card_base")).isEqualTo(1);
            assertThat(finds("price_current")).isEqualTo(1);
        } finally {
            db.runCommand(new Document("profile", 0));
        }
        List<Document> profiled = new ArrayList<>();
        db.getCollection("system.profile").find(new Document("op", "query")).into(profiled);
        assertThat(profiled).isNotEmpty();
        for (Document p : profiled) {
            String ns = p.getString("ns");
            if (ns == null || ns.endsWith("system.profile")) continue;
            assertThat(String.valueOf(p.get("planSummary"))).as(ns + " " + p.get("command")).doesNotContain("COLLSCAN");
        }
        assertThat(profiled.stream().map(d -> d.getString("ns")).filter(n -> n != null && n.endsWith(".products")).count())
                .as("one products find per request (two requests)").isEqualTo(2);
    }

    private Map<String, Integer> snapshotFinds() {
        Map<String, Integer> m = new LinkedHashMap<>();
        FINDS_BY_COLLECTION.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    @AfterEach
    void profilerOff() {
        db.runCommand(new Document("profile", 0));
    }

    // ---------- helpers for comparing bodies ----------

    /** A body without its per-request id, so two answers can be compared. */
    private static String strip(JsonNode body) {
        ObjectNode copy = ((ObjectNode) body).deepCopy();
        copy.remove("requestId");
        return copy.toString();
    }
}
