package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.auth.CustomerAccessTokenCodec;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.money.Currency;
import com.tazzzo.customer.address.AddressLimitProperties;
import com.tazzzo.customer.address.AddressRepository;
import com.tazzzo.customer.cart.CartEnricher;
import com.tazzzo.customer.cart.CartService;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-13A — checkout validation + quote, end to end over HTTP and (for transaction races) directly
 * against {@link CheckoutService} with a real Mongo transaction whose commit "fails transiently"
 * ({@link RetryInjectingTx}). No sleeps: timing is pinned with latches and DB state.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CheckoutQuoteIT.TestBeans.class})
class CheckoutQuoteIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));
    static final String VERTICAL = "TZV-000001";
    static final String PIN_OK = "560201";
    static final String PIN_NO = "560299";
    static final String QUOTE = "/v1/customer/checkout/quote";

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> ACCESS_KEY);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> REFRESH_KEY);
        r.add("tazzzo.freshness.enabled", () -> "true");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary
        AddressLimitProperties bigAddressLimit() {
            AddressLimitProperties p = new AddressLimitProperties();
            p.setMaxActiveAddresses(10);
            return p;
        }

        /** A REAL Tx that behaves normally until a test arms it (see RetryInjectingTx). */
        @Bean @Primary
        RetryInjectingTx retryInjectingTx(com.mongodb.client.MongoClient mongoClient) {
            return new RetryInjectingTx(mongoClient);
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired ServiceabilityService serviceability;
    @Autowired PricingService pricing;
    @Autowired InventoryService inventory;
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService changes;
    @Autowired MeterRegistry registry;
    @Autowired RetryInjectingTx httpRetryTx;
    @Autowired CustomerAccessTokenCodec accessCodec;
    @Autowired CartService cartService;
    @Autowired CartEnricher cartEnricher;
    @Autowired AddressRepository addressRepository;
    @Autowired CheckoutQuoteRepository quoteRepository;
    @Autowired CheckoutProperties checkoutProperties;
    @Autowired Clock clock;
    @Autowired org.springframework.beans.factory.ObjectProvider<CustomerIdentityAuthority> identityAuthority;

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand(PIN_OK, "SA-CHK-1",
                List.of(new ServiceabilityRoute("FUL-CHK-INTERNAL", 0, true)), "seed", null));
    }

    // ---------- fixtures ----------

    private int skuSeq = 0;

    private String sku(long sellingPaise, int onHand) {
        String id = "TZP-8" + String.format("%05d", ++skuSeq) + (System.nanoTime() % 1000);
        id = id.substring(0, Math.min(id.length(), 12));
        db.getCollection("products").insertOne(new Document("_id", id).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", "BR").append("title", "T " + id).append("lifecycle", "active")
                .append("classification", new Document("vertical_id", VERTICAL).append("release_id", "R1")
                        .append("status", "confirmed"))
                .append("attributes", new Document()).append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date()));
        pricing.upsertPrice(new UpsertPriceCommand(id, sellingPaise, sellingPaise + 2000, Currency.INR, null, null,
                "seed", null));
        inventory.setInventory(new SetInventoryCommand(id, "FUL-CHK-INTERNAL", onHand, 0, 10, "seed", null));
        return id;
    }

    private String token() {
        String phone = "+9197" + String.format("%08d", (System.nanoTime() / 7) % 100_000_000);
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody()
                .get("accessToken").asText();
    }

    private String customerId(String token) {
        return accessCodec.verify(token).customerId().value();
    }

    private String address(String token, String pin) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", "HOME");
        m.put("recipientName", "Name");
        m.put("recipientPhone", "+919876500001");
        m.put("addressLine1", "Line1");
        m.put("city", "City");
        m.put("state", "State");
        m.put("postalCode", pin);
        return post("/v1/customer/addresses", m, token, JsonNode.class).getBody().get("addressId").asText();
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, HttpHeaders extra, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        if (extra != null) h.putAll(extra);
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> cartPut(String token, String sku, int qty, long version) {
        HttpHeaders h = new HttpHeaders();
        h.set("If-Match", tag(version));
        return call(HttpMethod.PUT, "/v1/customer/cart/items/" + sku, token, h, Map.of("quantity", qty));
    }

    private ResponseEntity<JsonNode> cartGet(String token) {
        return call(HttpMethod.GET, "/v1/customer/cart", token, null, null);
    }

    private static String tag(long version) {
        return "\"cart-" + version + "\"";
    }

    private static String newKey() {
        return "idem-" + java.util.UUID.randomUUID();
    }

    private ResponseEntity<JsonNode> quote(String token, String ifMatch, String key, Object body) {
        HttpHeaders h = new HttpHeaders();
        if (ifMatch != null) h.set("If-Match", ifMatch);
        if (key != null) h.set("Idempotency-Key", key);
        return call(HttpMethod.POST, QUOTE, token, h, body);
    }

    private ResponseEntity<JsonNode> quote(String token, long cartVersion, String key, String addressId) {
        return quote(token, tag(cartVersion), key, Map.of("addressId", addressId));
    }

    private ResponseEntity<JsonNode> getQuote(String token, String quoteId) {
        return call(HttpMethod.GET, "/v1/customer/checkout/quotes/" + quoteId, token, null, null);
    }

    private long quoteCount(String token) {
        return db.getCollection("checkout_quotes").countDocuments(new Document("customerId", customerId(token)));
    }

    private double count(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asText();
    }

    /** A customer with one address at the serviceable PIN and a one-line cart at version 1. */
    private record Ready(String token, String address, String sku) { }

    private Ready ready(long price, int onHand, int qty) {
        String t = token();
        String a = address(t, PIN_OK);
        String s = sku(price, onHand);
        assertThat(cartPut(t, s, qty, 0).getStatusCode().value()).isEqualTo(200);
        return new Ready(t, a, s);
    }

    // ---------- auth / request shape ----------

    @Test void every_checkout_route_requires_customer_authentication() {
        assertThat(quote(null, tag(0), newKey(), Map.of("addressId", "ADDR_x123456")).getStatusCode().value()).isEqualTo(401);
        assertThat(getQuote(null, "CHKQ_abcdefgh").getStatusCode().value()).isEqualTo(401);
    }

    @Test void missing_and_malformed_idempotency_key() {
        Ready r = ready(1000, 5, 1);
        ResponseEntity<JsonNode> missing = quote(r.token(), tag(1), null, Map.of("addressId", r.address()));
        assertThat(missing.getStatusCode().value()).isEqualTo(428);
        assertThat(code(missing)).isEqualTo("IDEMPOTENCY_REQUIRED");
        for (String bad : List.of("short", "has space in it 123", "x".repeat(65), "bad/slash/key-12345")) {
            ResponseEntity<JsonNode> res = quote(r.token(), tag(1), bad, Map.of("addressId", r.address()));
            assertThat(res.getStatusCode().value()).as(bad).isEqualTo(400);
            assertThat(code(res)).isEqualTo("INVALID_REQUEST");
        }
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void missing_malformed_and_stale_cart_etag() {
        Ready r = ready(1000, 5, 1);
        Map<String, Object> body = Map.of("addressId", r.address());
        ResponseEntity<JsonNode> missing = quote(r.token(), null, newKey(), body);
        assertThat(missing.getStatusCode().value()).isEqualTo(428);
        assertThat(code(missing)).isEqualTo("PRECONDITION_REQUIRED");
        for (String bad : List.of("\"cart-x\"", "W/\"cart-1\"", "\"profile-1\"", "*")) {
            assertThat(quote(r.token(), bad, newKey(), body).getStatusCode().value()).as(bad).isEqualTo(400);
        }
        ResponseEntity<JsonNode> stale = quote(r.token(), tag(0), newKey(), body);
        assertThat(stale.getStatusCode().value()).isEqualTo(412);
        assertThat(code(stale)).isEqualTo("PRECONDITION_FAILED");
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void body_must_carry_an_address_id_string() {
        Ready r = ready(1000, 5, 1);
        assertThat(quote(r.token(), tag(1), newKey(), Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(quote(r.token(), tag(1), newKey(), Map.of("addressId", 5)).getStatusCode().value()).isEqualTo(400);
        assertThat(quote(r.token(), tag(1), newKey(), null).getStatusCode().value()).isEqualTo(400);
    }

    @Test void a_wrong_content_type_is_a_safe_415() {
        Ready r = ready(1000, 5, 1);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        h.setBearerAuth(r.token());
        h.set("If-Match", tag(1));
        h.set("Idempotency-Key", newKey());
        ResponseEntity<JsonNode> res = rest.exchange(url(QUOTE), HttpMethod.POST, new HttpEntity<>("addressId=x", h),
                JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(415);
        assertThat(code(res)).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(res.getBody().toString()).doesNotContain("springframework").doesNotContain("Exception");
    }

    // ---------- cart / address ----------

    @Test void an_empty_cart_is_rejected_deterministically() {
        String t = token();
        String a = address(t, PIN_OK);
        ResponseEntity<JsonNode> res = quote(t, 0, newKey(), a);
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(code(res)).isEqualTo("CHECKOUT_CART_EMPTY");
        // a cart emptied by the customer is the same
        String s = sku(1000, 5);
        cartPut(t, s, 1, 0);
        call(HttpMethod.DELETE, "/v1/customer/cart", t, hdr("If-Match", tag(1)), null);
        ResponseEntity<JsonNode> emptied = quote(t, 2, newKey(), a);
        assertThat(code(emptied)).isEqualTo("CHECKOUT_CART_EMPTY");
        assertThat(quoteCount(t)).isZero();
    }

    private static HttpHeaders hdr(String k, String v) {
        HttpHeaders h = new HttpHeaders();
        h.set(k, v);
        return h;
    }

    @Test void an_expired_cart_follows_the_cart_expiry_semantics_and_never_quotes() {
        Ready r = ready(1000, 5, 1);
        db.getCollection("customer_carts").updateOne(new Document("_id", customerId(r.token())),
                new Document("$set", new Document("expiresAt", Date.from(Instant.now().minusSeconds(10)))));
        // the version the client reviewed is now stale: reading applies the cart's expiry (advances it)
        assertThat(code(quote(r.token(), 1, newKey(), r.address()))).isEqualTo("PRECONDITION_FAILED");
        assertThat(code(quote(r.token(), 2, newKey(), r.address()))).isEqualTo("CHECKOUT_CART_EMPTY");
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void foreign_unknown_and_malformed_addresses_are_indistinguishable_404s() {
        Ready owner = ready(1000, 5, 1);
        String other = token();
        String foreign = address(other, PIN_OK);
        ResponseEntity<JsonNode> f = quote(owner.token(), 1, newKey(), foreign);
        ResponseEntity<JsonNode> u = quote(owner.token(), 1, newKey(), "ADDR_doesnotexist12345");
        ResponseEntity<JsonNode> m = quote(owner.token(), 1, newKey(), "not-an-address");
        for (ResponseEntity<JsonNode> res : List.of(f, u, m)) {
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            assertThat(code(res)).isEqualTo("NOT_FOUND");
        }
        assertThat(f.getBody().get("message")).isEqualTo(u.getBody().get("message"));
        assertThat(quoteCount(owner.token())).isZero();
    }

    // ---------- authoritative commerce validation ----------

    @Test void an_unserviceable_address_is_rejected() {
        String t = token();
        String a = address(t, PIN_NO);
        String s = sku(1000, 5);
        cartPut(t, s, 1, 0);
        ResponseEntity<JsonNode> res = quote(t, 1, newKey(), a);
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(code(res)).isEqualTo("CHECKOUT_UNSERVICEABLE");
        assertThat(quoteCount(t)).isZero();
    }

    private void assertItemRejected(Ready r, String sku, String reason) {
        ResponseEntity<JsonNode> res = quote(r.token(), 1, newKey(), r.address());
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(code(res)).isEqualTo("CHECKOUT_ITEM_UNAVAILABLE");
        JsonNode items = res.getBody().get("items");
        assertThat(items).isNotNull();
        boolean found = false;
        for (JsonNode i : items) {
            if (i.get("skuId").asText().equals(sku) && i.get("reason").asText().equals(reason)) found = true;
        }
        assertThat(found).as("expected " + reason + " for " + sku + " in " + items).isTrue();
        assertThat(res.getBody().toString()).doesNotContain("FUL-CHK").doesNotContain("SA-CHK");
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void a_product_that_became_unavailable_after_carting_is_rejected() {
        Ready r = ready(1000, 5, 1);
        db.getCollection("products").updateOne(new Document("_id", r.sku()),
                new Document("$set", new Document("lifecycle", "draft")));
        assertItemRejected(r, r.sku(), "PRODUCT_UNAVAILABLE");
    }

    @Test void a_missing_or_inactive_current_price_is_rejected() {
        Ready r = ready(1000, 5, 1);
        db.getCollection("price_current").deleteMany(new Document("sku_id", r.sku()));
        assertItemRejected(r, r.sku(), "PRICE_UNAVAILABLE");
    }

    @Test void out_of_stock_is_rejected() {
        Ready r = ready(1000, 0, 1);
        assertItemRejected(r, r.sku(), "OUT_OF_STOCK");
    }

    @Test void unknown_inventory_is_rejected_never_assumed_available() {
        Ready r = ready(1000, 5, 1);
        db.getCollection("inventory").deleteMany(new Document("sku_id", r.sku()));
        assertItemRejected(r, r.sku(), "STOCK_UNKNOWN");
    }

    @Test void a_quantity_above_the_current_maximum_is_rejected_never_reduced() {
        Ready r = ready(1000, 3, 5);
        assertItemRejected(r, r.sku(), "INSUFFICIENT_STOCK");
        assertThat(cartGet(r.token()).getBody().get("items").get(0).get("quantity").asInt()).as("cart untouched")
                .isEqualTo(5);
    }

    @Test void quote_creation_is_all_or_nothing() {
        String t = token();
        String a = address(t, PIN_OK);
        String good = sku(1000, 5);
        String bad = sku(2000, 0);
        cartPut(t, good, 1, 0);
        cartPut(t, bad, 1, 1);
        ResponseEntity<JsonNode> res = quote(t, 2, newKey(), a);
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().get("items")).hasSize(1);
        assertThat(quoteCount(t)).as("no partial quote").isZero();
    }

    // ---------- happy path ----------

    @Test void a_valid_cart_produces_a_quote_with_current_prices_totals_and_a_five_minute_lifetime() {
        String t = token();
        String a = address(t, PIN_OK);
        String s1 = sku(12345, 10);
        String s2 = sku(500, 10);
        cartPut(t, s1, 2, 0);
        cartPut(t, s2, 3, 1);
        // the price moved after carting: the quote takes the CURRENT canonical price
        pricing.upsertPrice(new UpsertPriceCommand(s2, 700, 900, Currency.INR, null, null, "seed", 1L));
        double successBefore = count("customer_checkout_quote_success");

        Map<String, Object> body = new HashMap<>();
        body.put("addressId", a);
        body.put("price", 1); // client-supplied values are never read
        body.put("subtotalPaise", 1);
        body.put("customerId", "CUS_evil");
        body.put("fulfillmentLocationId", "FUL-EVIL");
        ResponseEntity<JsonNode> res = quote(t, tag(2), newKey(), body);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode q = res.getBody();
        assertThat(q.get("quoteId").asText()).matches("^CHKQ_[A-Za-z0-9_-]+$");
        assertThat(q.get("cartVersion").asLong()).isEqualTo(2);
        assertThat(q.get("addressId").asText()).isEqualTo(a);
        assertThat(q.get("currency").asText()).isEqualTo("INR");
        assertThat(q.get("itemCount").asInt()).isEqualTo(5);
        assertThat(q.get("distinctItemCount").asInt()).isEqualTo(2);
        long expectedSubtotal = 12345L * 2 + 700L * 3;
        assertThat(q.get("subtotalPaise").asLong()).isEqualTo(expectedSubtotal);
        for (JsonNode i : q.get("items")) {
            if (i.get("skuId").asText().equals(s1)) {
                assertThat(i.get("unitPricePaise").asLong()).isEqualTo(12345);
                assertThat(i.get("lineTotalPaise").asLong()).isEqualTo(24690);
            } else {
                assertThat(i.get("skuId").asText()).isEqualTo(s2);
                assertThat(i.get("unitPricePaise").asLong()).as("current price, not the carted one").isEqualTo(700);
                assertThat(i.get("lineTotalPaise").asLong()).isEqualTo(2100);
            }
        }
        assertThat(Instant.parse(q.get("expiresAt").asText()).getEpochSecond()
                - Instant.parse(q.get("createdAt").asText()).getEpochSecond()).isEqualTo(300);
        assertThat(q.get("requestId").asText()).isNotBlank();
        assertThat(q.toString()).doesNotContain("FUL-CHK").doesNotContain("SA-CHK").doesNotContain("FUL-EVIL")
                .doesNotContain("fulfillment").doesNotContain("customerId").doesNotContain("CUS_");

        Document stored = db.getCollection("checkout_quotes").find(new Document("_id", q.get("quoteId").asText())).first();
        assertThat(stored.getString("customerId")).isEqualTo(customerId(t));
        assertThat(stored.get("subtotalPaise", Number.class).longValue()).isEqualTo(expectedSubtotal);
        assertThat(stored.toJson()).doesNotContain("FUL-CHK").doesNotContain("SA-CHK").doesNotContain("560201")
                .doesNotContain("title").doesNotContain("FUL-EVIL");
        assertThat(count("customer_checkout_quote_success") - successBefore).isEqualTo(1);
        assertThat(cartGet(t).getBody().get("version").asLong()).as("checkout never mutates the cart").isEqualTo(2);
    }

    // ---------- idempotency ----------

    @Test void the_same_key_and_input_replays_the_original_quote_without_extending_expiry() {
        Ready r = ready(1000, 5, 2);
        String key = newKey();
        ResponseEntity<JsonNode> first = quote(r.token(), 1, key, r.address());
        pricing.upsertPrice(new UpsertPriceCommand(r.sku(), 9000, 9500, Currency.INR, null, null, "seed", 1L));
        ResponseEntity<JsonNode> again = quote(r.token(), 1, key, r.address());
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("quoteId")).isEqualTo(first.getBody().get("quoteId"));
        assertThat(again.getBody().get("expiresAt")).as("replay never extends expiry").isEqualTo(first.getBody().get("expiresAt"));
        assertThat(again.getBody().get("subtotalPaise").asLong()).as("never re-priced").isEqualTo(2000);
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    @Test void the_same_key_with_a_different_address_or_cart_version_is_a_conflict() {
        Ready r = ready(1000, 5, 1);
        String key = newKey();
        assertThat(quote(r.token(), 1, key, r.address()).getStatusCode().value()).isEqualTo(200);
        String other = address(r.token(), PIN_OK);
        ResponseEntity<JsonNode> diffAddress = quote(r.token(), 1, key, other);
        assertThat(diffAddress.getStatusCode().value()).isEqualTo(409);
        assertThat(code(diffAddress)).isEqualTo("IDEMPOTENCY_CONFLICT");
        cartPut(r.token(), r.sku(), 2, 1);
        ResponseEntity<JsonNode> diffVersion = quote(r.token(), 2, key, r.address());
        assertThat(diffVersion.getStatusCode().value()).isEqualTo(409);
        assertThat(code(diffVersion)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    @Test void idempotency_is_scoped_per_customer() {
        Ready a = ready(1000, 5, 1);
        Ready b = ready(1000, 5, 1);
        String key = newKey();
        String qa = quote(a.token(), 1, key, a.address()).getBody().get("quoteId").asText();
        String qb = quote(b.token(), 1, key, b.address()).getBody().get("quoteId").asText();
        assertThat(qa).isNotEqualTo(qb);
    }

    @Test void concurrent_same_key_requests_resolve_to_one_durable_quote() throws Exception {
        Ready r = ready(1000, 10, 1);
        String key = newKey();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return quote(r.token(), 1, key, r.address());
                }));
            }
            go.countDown();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Future<ResponseEntity<JsonNode>> f : calls) {
                ResponseEntity<JsonNode> res = f.get(30, TimeUnit.SECONDS);
                assertThat(res.getStatusCode().value()).isEqualTo(200);
                ids.add(res.getBody().get("quoteId").asText());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    // ---------- read ----------

    @Test void a_quote_can_be_read_by_its_owner_only_and_expires_as_410() {
        Ready r = ready(1000, 5, 1);
        String id = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        double readBefore = count("customer_checkout_quote_read_success");
        ResponseEntity<JsonNode> read = getQuote(r.token(), id);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        assertThat(read.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(read.getBody().get("quoteId").asText()).isEqualTo(id);
        assertThat(count("customer_checkout_quote_read_success") - readBefore).isEqualTo(1);

        Ready stranger = ready(1000, 5, 1);
        ResponseEntity<JsonNode> foreign = getQuote(stranger.token(), id);
        ResponseEntity<JsonNode> unknown = getQuote(stranger.token(), "CHKQ_doesnotexist12345");
        ResponseEntity<JsonNode> malformed = getQuote(stranger.token(), "nope");
        for (ResponseEntity<JsonNode> res : List.of(foreign, unknown, malformed)) {
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            assertThat(code(res)).isEqualTo("NOT_FOUND");
        }

        db.getCollection("checkout_quotes").updateOne(new Document("_id", id),
                new Document("$set", new Document("expiresAt", Date.from(Instant.now().minusSeconds(1)))));
        ResponseEntity<JsonNode> expired = getQuote(r.token(), id);
        assertThat(expired.getStatusCode().value()).isEqualTo(410);
        assertThat(code(expired)).isEqualTo("QUOTE_EXPIRED");
        assertThat(db.getCollection("checkout_quotes").countDocuments(new Document("_id", id)))
                .as("expiry is derived, the quote is never deleted").isEqualTo(1);
    }

    // ---------- observability ----------

    @Test void metrics_are_bounded_counted_once_and_carry_no_identifiers() {
        Ready r = ready(1000, 0, 1); // out of stock -> item rejection
        double failBefore = count("customer_checkout_failure", "operation", "create_quote",
                "reason", "checkout_item_unavailable");
        double rejectBefore = count("customer_checkout_item_rejection", "reason", "out_of_stock");
        double successBefore = count("customer_checkout_quote_success");

        assertThat(quote(r.token(), 1, newKey(), r.address()).getStatusCode().value()).isEqualTo(409);

        assertThat(count("customer_checkout_failure", "operation", "create_quote",
                "reason", "checkout_item_unavailable") - failBefore).as("failure counted exactly once").isEqualTo(1);
        assertThat(count("customer_checkout_item_rejection", "reason", "out_of_stock") - rejectBefore).isEqualTo(1);
        assertThat(count("customer_checkout_quote_success")).as("no success metric on failure").isEqualTo(successBefore);
        for (Meter m : registry.getMeters()) {
            if (!m.getId().getName().startsWith("customer_checkout")) continue;
            for (Tag tg : m.getId().getTags()) {
                assertThat(tg.getValue()).doesNotContain("TZP-").doesNotContain("CUS_").doesNotContain("ADDR_")
                        .doesNotContain("CHKQ_").doesNotContain("idem-");
                assertThat(tg.getKey()).isIn("operation", "reason");
            }
        }
    }

    // ---------- transaction races (service level, real Mongo txn + forced retries) ----------

    private CheckoutService serviceWith(RetryInjectingTx tx) {
        return new CheckoutService(cartService, cartEnricher, addressRepository, quoteRepository, checkoutProperties,
                clock, identityAuthority, tx);
    }

    @Test void a_forced_transaction_retry_persists_exactly_one_quote_and_returns_the_committed_one() {
        Ready r = ready(1000, 5, 1);
        CustomerId cid = new CustomerId(customerId(r.token()));
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(1);
        CheckoutQuote q = serviceWith(retryTx).createQuote(cid, 1, newKey(), r.address(), "req");

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(retryTx.attemptResults()).extracting(o -> ((CheckoutQuote) o).quoteId())
                .as("the quote id is retry-stable").containsExactly(q.quoteId(), q.quoteId());
        assertThat(quoteCount(r.token())).isEqualTo(1);
        assertThat(db.getCollection("checkout_quotes").find(new Document("customerId", cid.value())).first()
                .getString("_id")).isEqualTo(q.quoteId());
    }

    @Test void a_retry_that_finds_a_competing_committed_quote_returns_the_winner_not_the_stale_attempt() {
        Ready r = ready(1000, 5, 1);
        CustomerId cid = new CustomerId(customerId(r.token()));
        String key = newKey();
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        List<CheckoutQuote> competitor = new ArrayList<>();
        retryTx.arm(1, n -> {
            if (n == 2) {
                competitor.add(serviceWith(new RetryInjectingTx(client)).createQuote(cid, 1, key, r.address(), "req"));
            }
        });
        CheckoutQuote result = serviceWith(retryTx).createQuote(cid, 1, key, r.address(), "req");

        assertThat(competitor).hasSize(1);
        assertThat(result.quoteId()).isEqualTo(competitor.get(0).quoteId());
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    @Test void a_cart_changed_after_validation_but_before_the_transaction_creates_no_quote() {
        Ready r = ready(1000, 5, 1);
        CustomerId cid = new CustomerId(customerId(r.token()));
        String extra = sku(3000, 5);
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(0, n -> cartService.setItem(cid, extra, 1, 1)); // commits cart v2 before the txn reads
        assertThatThrownBy(() -> serviceWith(retryTx).createQuote(cid, 1, newKey(), r.address(), "req"))
                .isInstanceOf(CheckoutFailure.class)
                .satisfies(e -> assertThat(((CheckoutFailure) e).reason())
                        .isEqualTo(CheckoutFailure.Reason.PRECONDITION_FAILED));
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void a_cart_that_expires_before_the_transactional_recheck_creates_no_quote() {
        Ready r = ready(1000, 5, 1);
        CustomerId cid = new CustomerId(customerId(r.token()));
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(0, n -> db.getCollection("customer_carts").updateOne(new Document("_id", cid.value()),
                new Document("$set", new Document("expiresAt", Date.from(Instant.now().minusSeconds(5))))));
        assertThatThrownBy(() -> serviceWith(retryTx).createQuote(cid, 1, newKey(), r.address(), "req"))
                .isInstanceOf(CheckoutFailure.class)
                .satisfies(e -> assertThat(((CheckoutFailure) e).reason())
                        .isEqualTo(CheckoutFailure.Reason.CHECKOUT_CART_EMPTY));
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void a_customer_identity_that_disappears_before_the_transaction_creates_no_quote() {
        Ready r = ready(1000, 5, 1);
        CustomerId cid = new CustomerId(customerId(r.token()));
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(0, n -> db.getCollection("customers").deleteOne(new Document("_id", cid.value())));
        assertThatThrownBy(() -> serviceWith(retryTx).createQuote(cid, 1, newKey(), r.address(), "req"))
                .isInstanceOf(CheckoutFailure.class)
                .satisfies(e -> assertThat(((CheckoutFailure) e).reason())
                        .isEqualTo(CheckoutFailure.Reason.UNAVAILABLE));
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void a_quote_created_through_a_retried_transaction_counts_one_success_and_persists_one_quote() {
        Ready r = ready(1000, 5, 1);
        double successBefore = count("customer_checkout_quote_success");
        httpRetryTx.arm(1); // attempt 1 rolled back by a transient commit error, attempt 2 commits
        ResponseEntity<JsonNode> res = quote(r.token(), 1, newKey(), r.address());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(httpRetryTx.attempts()).isEqualTo(2);
        assertThat(quoteCount(r.token())).isEqualTo(1);
        assertThat(count("customer_checkout_quote_success") - successBefore).as("success counted once, after commit")
                .isEqualTo(1);
        assertThat(db.getCollection("checkout_quotes").find(new Document("customerId", customerId(r.token()))).first()
                .getString("_id")).isEqualTo(res.getBody().get("quoteId").asText());
    }
}
