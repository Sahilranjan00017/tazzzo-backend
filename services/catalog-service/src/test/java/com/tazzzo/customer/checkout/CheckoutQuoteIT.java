package com.tazzzo.customer.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.benefits.BenefitsEvaluationPort;
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
    @Autowired com.tazzzo.benefits.BenefitsEvaluationPort wiredBenefits;
    @Autowired com.tazzzo.benefits.BenefitsObservability benefitsObservability;
    @Autowired com.tazzzo.membership.MembershipService memberships;
    @Autowired com.tazzzo.membership.MembershipTerminationService membershipTermination;
    @Autowired com.tazzzo.membership.MembershipEntitlementPort membershipPort;
    @Autowired com.tazzzo.customer.order.OrderService orderService;
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
                new Document("$set", new Document("createdAt", Date.from(Instant.now().minusSeconds(400)))
                        .append("expiresAt", Date.from(Instant.now().minusSeconds(1)))));
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
        return serviceWith(tx, wiredBenefits);
    }

    private CheckoutService serviceWith(RetryInjectingTx tx, BenefitsEvaluationPort benefits) {
        return serviceWith(tx, quoteRepository, benefits);
    }

    private CheckoutService serviceWith(RetryInjectingTx tx, CheckoutQuoteRepository quotes,
                                        BenefitsEvaluationPort benefits) {
        return new CheckoutService(cartService, cartEnricher, addressRepository, quotes, checkoutProperties,
                clock, identityAuthority, benefits, tx);
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

    // ---------- M1: expired idempotent replay ----------

    @Test void an_active_replay_returns_the_exact_original_quote() {
        Ready r = ready(1000, 5, 2);
        String key = newKey();
        ResponseEntity<JsonNode> first = quote(r.token(), 1, key, r.address());
        ResponseEntity<JsonNode> again = quote(r.token(), 1, key, r.address());
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        ObjectNode a = (ObjectNode) again.getBody();
        ObjectNode f = (ObjectNode) first.getBody();
        a.remove("requestId");
        f.remove("requestId");
        assertThat(a).isEqualTo(f);
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    private void expire(String quoteId) {
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId),
                new Document("$set", new Document("createdAt", Date.from(Instant.now().minusSeconds(400)))
                        .append("expiresAt", Date.from(Instant.now().minusSeconds(1)))));
    }

    @Test void a_replay_of_an_expired_quote_is_410_never_200_never_a_new_quote_never_repriced() {
        Ready r = ready(1000, 5, 2);
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        expire(quoteId);
        Date expiredAt = db.getCollection("checkout_quotes").find(new Document("_id", quoteId)).first()
                .getDate("expiresAt");
        pricing.upsertPrice(new UpsertPriceCommand(r.sku(), 9999, 12000, Currency.INR, null, null, "seed", 1L));

        ResponseEntity<JsonNode> replay = quote(r.token(), 1, key, r.address());

        assertThat(replay.getStatusCode().value()).isEqualTo(410);
        assertThat(code(replay)).isEqualTo("QUOTE_EXPIRED");
        assertThat(quoteCount(r.token())).as("no replacement quote created").isEqualTo(1);
        Document stillStored = db.getCollection("checkout_quotes").find(new Document("_id", quoteId)).first();
        assertThat(stillStored.getDate("expiresAt")).as("expiry never extended by a replay").isEqualTo(expiredAt);
        assertThat(stillStored.get("subtotalPaise", Number.class).longValue()).as("never re-priced").isEqualTo(2000L);
    }

    @Test void a_new_quote_after_expiry_requires_a_new_idempotency_key() {
        Ready r = ready(1000, 5, 1);
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        expire(quoteId);
        assertThat(code(quote(r.token(), 1, key, r.address()))).isEqualTo("QUOTE_EXPIRED");

        ResponseEntity<JsonNode> fresh = quote(r.token(), 1, newKey(), r.address());
        assertThat(fresh.getStatusCode().value()).isEqualTo(200);
        assertThat(fresh.getBody().get("quoteId").asText()).isNotEqualTo(quoteId);
        assertThat(quoteCount(r.token())).isEqualTo(2);
    }

    @Test void a_concurrent_duplicate_key_race_against_an_expired_winner_is_410_for_every_caller() throws Exception {
        Ready r = ready(1000, 5, 1);
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        expire(quoteId);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(pool.submit(() -> quote(r.token(), 1, key, r.address())));
            }
            for (Future<ResponseEntity<JsonNode>> f : calls) {
                ResponseEntity<JsonNode> res = f.get(30, TimeUnit.SECONDS);
                assertThat(res.getStatusCode().value()).isEqualTo(410);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    // ---------- M2: address TOCTOU (deletion and version) ----------

    private ResponseEntity<Void> deleteAddress(String token, String addressId, long version) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("If-Match", "\"address-" + version + "\"");
        return rest.exchange(url("/v1/customer/addresses/" + addressId), HttpMethod.DELETE, new HttpEntity<>(h),
                Void.class);
    }

    private ResponseEntity<JsonNode> patchAddress(String token, String addressId, long version, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("If-Match", "\"address-" + version + "\"");
        return rest.exchange(url("/v1/customer/addresses/" + addressId), HttpMethod.PATCH, new HttpEntity<>(body, h),
                JsonNode.class);
    }

    @Test void an_address_deleted_after_validation_but_before_the_transaction_yields_404_and_no_quote() {
        Ready r = ready(1000, 5, 1);
        CustomerId cid = new CustomerId(customerId(r.token()));
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(0, n -> assertThat(deleteAddress(r.token(), r.address(), 1).getStatusCode().value())
                .isEqualTo(204));
        assertThatThrownBy(() -> serviceWith(retryTx).createQuote(cid, 1, newKey(), r.address(), "req"))
                .isInstanceOf(CheckoutFailure.class)
                .satisfies(e -> assertThat(((CheckoutFailure) e).reason()).isEqualTo(CheckoutFailure.Reason.NOT_FOUND));
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void an_address_whose_postal_code_changes_after_validation_yields_404_not_a_stale_quote() {
        Ready r = ready(1000, 5, 1);
        CustomerId cid = new CustomerId(customerId(r.token()));
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        // version bumps from 1 -> 2 with a DIFFERENT (still serviceable) PIN before the transaction reads it
        retryTx.arm(0, n -> assertThat(patchAddress(r.token(), r.address(), 1, Map.of("postalCode", PIN_OK))
                .getStatusCode().value()).isEqualTo(200));
        assertThatThrownBy(() -> serviceWith(retryTx).createQuote(cid, 1, newKey(), r.address(), "req"))
                .isInstanceOf(CheckoutFailure.class)
                .satisfies(e -> assertThat(((CheckoutFailure) e).reason()).isEqualTo(CheckoutFailure.Reason.NOT_FOUND));
        assertThat(quoteCount(r.token())).isZero();
        // the address itself is fine (still exists, still owned) -- ONLY the stale-version recheck rejects
        assertThat(getQuote(r.token(), "CHKQ_doesnotexist12345").getStatusCode().value()).isEqualTo(404); // sanity: routes still healthy
    }

    @Test void an_untouched_address_still_produces_a_quote_after_the_version_recheck() {
        Ready r = ready(1000, 5, 1);
        ResponseEntity<JsonNode> res = quote(r.token(), 1, newKey(), r.address());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("addressId").asText()).isEqualTo(r.address());
    }

    // ---------- M3: quote invariants surface as a safe, counted 500 ----------

    private Ready quotedReady() {
        return ready(1000, 5, 1);
    }

    private void corrupt(String quoteId, Document mutation) {
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId), new Document("$set", mutation));
    }

    @Test void a_corrupt_stored_quote_line_total_is_a_safe_500_on_get_counted_once_as_internal() {
        Ready r = quotedReady();
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        Document bad = new Document("skuId", r.sku()).append("quantity", 1).append("unitPricePaise", 1000L)
                .append("lineTotalPaise", 999L); // does not equal unitPricePaise * quantity
        corrupt(quoteId, new Document("items", List.of(bad)));

        double before = count("customer_checkout_failure", "operation", "read_quote", "reason", "internal");
        double allBefore = count("customer_checkout_failure");
        ResponseEntity<JsonNode> res = getQuote(r.token(), quoteId);
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INTERNAL");
        assertThat(res.getBody().toString()).doesNotContain("lineTotalPaise").doesNotContain("unitPricePaise")
                .doesNotContain("IllegalArgumentException").doesNotContain("skuId");
        assertThat(count("customer_checkout_failure", "operation", "read_quote", "reason", "internal") - before)
                .isEqualTo(1);
        assertThat(count("customer_checkout_failure") - allBefore).isEqualTo(1);
    }

    @Test void a_corrupt_stored_subtotal_mismatch_is_a_safe_500() {
        Ready r = quotedReady();
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        corrupt(quoteId, new Document("subtotalPaise", 555L));
        ResponseEntity<JsonNode> res = getQuote(r.token(), quoteId);
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INTERNAL");
    }

    @Test void a_corrupt_stored_duplicate_sku_is_a_safe_500() {
        Ready r = quotedReady();
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        Document line = new Document("skuId", r.sku()).append("quantity", 1).append("unitPricePaise", 1000L)
                .append("lineTotalPaise", 1000L);
        corrupt(quoteId, new Document("items", List.of(line, line)).append("itemCount", 2)
                .append("subtotalPaise", 2000L));
        ResponseEntity<JsonNode> res = getQuote(r.token(), quoteId);
        assertThat(res.getStatusCode().value()).isEqualTo(500);
    }

    @Test void a_corrupt_stored_createdAt_not_before_expiresAt_is_a_safe_500() {
        Ready r = quotedReady();
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        Date same = new Date();
        corrupt(quoteId, new Document("createdAt", same).append("expiresAt", same));
        ResponseEntity<JsonNode> res = getQuote(r.token(), quoteId);
        assertThat(res.getStatusCode().value()).isEqualTo(500);
    }

    @Test void a_corrupt_stored_currency_is_a_safe_500() {
        Ready r = quotedReady();
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        corrupt(quoteId, new Document("currency", "USD"));
        ResponseEntity<JsonNode> res = getQuote(r.token(), quoteId);
        assertThat(res.getStatusCode().value()).isEqualTo(500);
    }

    @Test void an_idempotency_replay_of_a_corrupt_stored_quote_is_also_a_safe_500() {
        Ready r = quotedReady();
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        corrupt(quoteId, new Document("subtotalPaise", -1L));
        ResponseEntity<JsonNode> res = quote(r.token(), 1, key, r.address());
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INTERNAL");
    }

    // ---------- PR-13B: address-version provenance ----------

    private long storedAddressVersion(String quoteId) {
        return db.getCollection("checkout_quotes").find(new Document("_id", quoteId)).first()
                .get("addressVersion", Number.class).longValue();
    }

    @Test void a_valid_quote_persists_the_exact_address_version_commerce_validation_ran_against() {
        Ready r = ready(1000, 5, 1);
        // the address is still at its original version (1) when the quote is created
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        assertThat(storedAddressVersion(quoteId)).isEqualTo(1L);

        Document raw = db.getCollection("checkout_quotes").find(new Document("_id", quoteId)).first();
        CheckoutQuote roundTripped = CheckoutQuoteRepository.toQuote(raw);
        assertThat(roundTripped.addressVersion()).isEqualTo(1L);
    }

    @Test void idempotent_replay_preserves_the_original_address_version_never_the_latest() {
        Ready r = ready(1000, 5, 1);
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        long originalVersion = storedAddressVersion(quoteId);

        // the address is edited afterward (version advances) -- a replay must NOT pick up the new version
        assertThat(patchAddress(r.token(), r.address(), originalVersion, Map.of("recipientName", "Someone Else"))
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> replay = quote(r.token(), 1, key, r.address());
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("quoteId").asText()).isEqualTo(quoteId);
        assertThat(storedAddressVersion(quoteId)).as("replay never refreshes provenance").isEqualTo(originalVersion);
    }

    @Test void a_persisted_quote_missing_addressVersion_is_a_safe_500_on_get_never_200_404_410_or_503() {
        Ready r = quotedReady();
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId),
                new Document("$unset", new Document("addressVersion", "")));

        double before = count("customer_checkout_failure", "operation", "read_quote", "reason", "internal");
        ResponseEntity<JsonNode> res = getQuote(r.token(), quoteId);

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INTERNAL");
        assertThat(res.getBody().toString()).doesNotContain("addressVersion").doesNotContain("Mongo")
                .doesNotContain("IllegalArgumentException").doesNotContain(r.address());
        assertThat(count("customer_checkout_failure", "operation", "read_quote", "reason", "internal") - before)
                .isEqualTo(1);
    }

    @Test void an_idempotency_replay_of_a_quote_missing_addressVersion_is_also_a_safe_500() {
        Ready r = quotedReady();
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId),
                new Document("$unset", new Document("addressVersion", "")));

        ResponseEntity<JsonNode> res = quote(r.token(), 1, key, r.address());
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INTERNAL");
    }

    @Test void addressVersion_never_appears_in_the_public_response_or_error_bodies() {
        Ready r = ready(1000, 5, 1);
        ResponseEntity<JsonNode> created = quote(r.token(), 1, newKey(), r.address());
        assertThat(created.getBody().toString()).doesNotContain("addressVersion");
        assertThat(created.getBody().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("quoteId", "cartVersion", "addressId", "items", "itemCount",
                        "distinctItemCount", "subtotalPaise", "currency", "createdAt", "expiresAt", "benefitPreview",
                        "moneyPreview", "requestId");

        String quoteId = created.getBody().get("quoteId").asText();
        ResponseEntity<JsonNode> read = getQuote(r.token(), quoteId);
        assertThat(read.getBody().toString()).doesNotContain("addressVersion");

        // an error body must not leak it either (a stale cart-version rejection)
        ResponseEntity<JsonNode> conflict = quote(r.token(), 0, newKey(), r.address());
        assertThat(conflict.getStatusCode().value()).isEqualTo(412);
        assertThat(conflict.getBody().toString()).doesNotContain("addressVersion");
    }

    // ============================================================
    // PR-19A-1 -- the ADVISORY Checkout Benefits snapshot (internal only, persisted with the quote)
    // ============================================================

    private static final String BENEFIT_PLAN = "TAZZZO_PLUS_MONTHLY";

    private BenefitsEvaluationPort realBenefits(com.tazzzo.benefits.BenefitRule... rules) {
        return new com.tazzzo.benefits.BenefitsEvaluationService(membershipPort,
                new com.tazzzo.benefits.ConfigBackedBenefitRuleSource(List.of(rules)), benefitsObservability);
    }

    // TEST FIXTURES only (never launch policy)
    private static com.tazzzo.benefits.BenefitRule benefitRule(long minimumPaise, int bps) {
        return new com.tazzzo.benefits.BenefitRule(BENEFIT_PLAN, 1,
                com.tazzzo.common.money.Money.ofInrPaise(minimumPaise), new com.tazzzo.benefits.DiscountBps(bps));
    }

    private com.tazzzo.membership.Membership grantMembership(CustomerId customer) {
        return memberships.grant(customer, BENEFIT_PLAN, 1, "REF-" + java.util.UUID.randomUUID());
    }

    private Document storedQuote(String quoteId) {
        return db.getCollection("checkout_quotes").find(new Document("_id", quoteId)).first();
    }

    /** A customer whose cart is 2 x 5000 = 10,000 paise at the serviceable PIN, cart version 1. */
    private Ready tenThousand() {
        return ready(5000, 10, 2);
    }

    private static final BenefitsEvaluationPort MUST_NOT_BE_CALLED = (c, subtotal) -> {
        throw new AssertionError("Benefits must NOT be evaluated here");
    };

    private CheckoutQuote create(Ready r, BenefitsEvaluationPort benefits) {
        return serviceWith(new RetryInjectingTx(client), benefits).createQuote(
                new CustomerId(customerId(r.token())), 1, newKey(), r.address(), "req");
    }

    @Test void a_quote_for_a_customer_without_membership_persists_a_NO_MEMBERSHIP_snapshot() {
        Ready r = tenThousand();

        CheckoutQuote q = create(r, realBenefits(benefitRule(10_000, 500)));

        assertThat(q.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.NoBenefit(10_000,
                com.tazzzo.benefits.BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
        Document stored = (Document) storedQuote(q.quoteId()).get("benefits");
        assertThat(stored.keySet()).containsExactlyInAnyOrder("outcome", "eligibleSubtotalPaise", "noBenefitReason");
        assertThat(stored.getString("noBenefitReason")).isEqualTo("NO_MEMBERSHIP");
    }

    @Test void a_member_with_no_configured_rule_persists_a_NO_RULE_snapshot_the_production_default() {
        Ready r = tenThousand();
        grantMembership(new CustomerId(customerId(r.token())));

        CheckoutQuote q = create(r, realBenefits());

        assertThat(q.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.NoBenefit(10_000,
                com.tazzzo.benefits.BenefitEvaluation.NoBenefitReason.NO_RULE));
    }

    @Test void a_member_below_the_threshold_persists_a_NOT_ELIGIBLE_snapshot() {
        Ready r = tenThousand();
        grantMembership(new CustomerId(customerId(r.token())));

        CheckoutQuote q = create(r, realBenefits(benefitRule(10_001, 500)));

        assertThat(q.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.NoBenefit(10_000,
                com.tazzzo.benefits.BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE));
    }

    @Test void a_member_at_the_threshold_persists_an_APPLIED_snapshot_without_membership_or_plan_identity() {
        Ready r = tenThousand();
        grantMembership(new CustomerId(customerId(r.token())));

        CheckoutQuote q = create(r, realBenefits(benefitRule(10_000, 500)));

        assertThat(q.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.Applied(10_000, 500, 500));
        Document stored = (Document) storedQuote(q.quoteId()).get("benefits");
        assertThat(stored.keySet()).containsExactlyInAnyOrder("outcome", "eligibleSubtotalPaise", "discountPaise",
                "discountBps");
        assertThat(stored.get("discountPaise")).isEqualTo(500L);
    }

    @Test void canonical_quote_money_is_unchanged_and_no_discount_or_payable_field_is_persisted() {
        Ready r = tenThousand();
        grantMembership(new CustomerId(customerId(r.token())));

        CheckoutQuote q = create(r, realBenefits(benefitRule(10_000, 500)));

        assertThat(q.subtotalPaise()).isEqualTo(10_000);
        assertThat(q.lines()).hasSize(1);
        assertThat(q.lines().get(0).unitPricePaise()).isEqualTo(5_000);
        assertThat(q.lines().get(0).lineTotalPaise()).isEqualTo(10_000);
        Document doc = storedQuote(q.quoteId());
        assertThat(doc.get("subtotalPaise")).isEqualTo(10_000L);
        assertThat(doc.keySet()).doesNotContain("discountPaise", "payablePaise", "amountDue", "grandTotal",
                "finalTotal", "netSubtotalPaise", "discountedSubtotalPaise");
        assertThat(doc.getList("items", Document.class).get(0).keySet())
                .containsExactlyInAnyOrder("skuId", "quantity", "unitPricePaise", "lineTotalPaise");
    }

    @Test void the_http_quote_persists_a_snapshot_with_the_wired_production_configuration_and_exposes_only_applied_false() {
        Ready r = tenThousand();

        ResponseEntity<JsonNode> res = quote(r.token(), 1, newKey(), r.address());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("benefitPreview").toString()).as("zero production rules: nothing is applied")
                .isEqualTo("{\"applied\":false}");
        assertThat(res.getBody().toString().toLowerCase()).doesNotContain("no_rule").doesNotContain("no_membership")
                .doesNotContain("reason").doesNotContain("membershipid").doesNotContain("planid")
                .doesNotContain("eligible").doesNotContain("amountDue");
        Document stored = (Document) storedQuote(res.getBody().get("quoteId").asText()).get("benefits");
        assertThat(stored.getString("outcome")).isEqualTo("NO_BENEFIT");
        assertThat(stored.getString("noBenefitReason")).isEqualTo("NO_MEMBERSHIP");
    }

    @Test void benefits_is_evaluated_exactly_once_per_creation_even_across_a_forced_transaction_retry() {
        Ready r = tenThousand();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        BenefitsEvaluationPort counting = (c, subtotal) -> {
            calls.incrementAndGet();
            return realBenefits().evaluate(c, subtotal);
        };
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(1);

        serviceWith(retryTx, counting).createQuote(new CustomerId(customerId(r.token())), 1, newKey(), r.address(),
                "req");

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(calls.get()).as("evaluated before the transaction, so a retry never re-evaluates").isEqualTo(1);
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    // ---------- fail closed ----------

    @Test void a_benefits_outage_fails_the_quote_closed_and_persists_nothing() {
        Ready r = tenThousand();
        BenefitsEvaluationPort down = (c, subtotal) -> {
            throw new com.tazzzo.benefits.BenefitsFailure(com.tazzzo.benefits.BenefitsFailure.Reason.UNAVAILABLE, "x");
        };

        assertThatThrownBy(() -> create(r, down)).isInstanceOf(CheckoutFailure.class)
                .satisfies(e -> assertThat(((CheckoutFailure) e).reason()).isEqualTo(CheckoutFailure.Reason.UNAVAILABLE));
        assertThat(quoteCount(r.token())).isZero();
    }

    @Test void benefits_integrity_and_invalid_request_failures_are_uncaught_integrity_defects_and_persist_nothing() {
        for (com.tazzzo.benefits.BenefitsFailure.Reason reason : List.of(
                com.tazzzo.benefits.BenefitsFailure.Reason.INTEGRITY_FAILURE,
                com.tazzzo.benefits.BenefitsFailure.Reason.INVALID_REQUEST)) {
            Ready r = tenThousand();
            BenefitsEvaluationPort failing = (c, subtotal) -> {
                throw new com.tazzzo.benefits.BenefitsFailure(reason, "internal detail");
            };

            assertThatThrownBy(() -> create(r, failing)).isInstanceOf(IllegalStateException.class)
                    .isNotInstanceOf(CheckoutFailure.class).hasMessageNotContaining("internal detail");
            assertThat(quoteCount(r.token())).as("%s", reason).isZero();
        }
    }

    // ---------- replay and GET never re-evaluate ----------

    @Test void a_replay_returns_the_stored_quote_and_snapshot_unchanged_after_a_membership_change() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        com.tazzzo.membership.Membership term = grantMembership(cid);
        String key = newKey();
        CheckoutQuote first = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, key, r.address(), "req");
        assertThat(first.benefitSnapshot()).isInstanceOf(CheckoutBenefitSnapshot.Applied.class);
        membershipTermination.revoke(term.membershipId()); // Membership changed after the quote
        Document before = storedQuote(first.quoteId());

        CheckoutQuote replay = serviceWith(new RetryInjectingTx(client), MUST_NOT_BE_CALLED)
                .createQuote(cid, 1, key, r.address(), "req");

        assertThat(replay).isEqualTo(first);
        assertThat(replay.benefitSnapshot()).isEqualTo(first.benefitSnapshot());
        assertThat(storedQuote(first.quoteId())).as("the stored quote is untouched").isEqualTo(before);
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    @Test void a_replay_ignores_a_benefits_configuration_change_after_the_quote() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        grantMembership(cid);
        String key = newKey();
        CheckoutQuote first = serviceWith(new RetryInjectingTx(client), realBenefits())
                .createQuote(cid, 1, key, r.address(), "req");
        assertThat(first.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.NoBenefit(10_000,
                com.tazzzo.benefits.BenefitEvaluation.NoBenefitReason.NO_RULE));

        // a later deployment configures a rule: the replay must not follow it
        CheckoutQuote replay = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(1_000, 9_000)))
                .createQuote(cid, 1, key, r.address(), "req");

        assertThat(replay).isEqualTo(first);
        assertThat(replay.benefitSnapshot()).isInstanceOf(CheckoutBenefitSnapshot.NoBenefit.class);
    }

    @Test void get_returns_the_stored_snapshot_and_never_evaluates_benefits() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        grantMembership(cid);
        CheckoutQuote created = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, newKey(), r.address(), "req");

        CheckoutQuote read = serviceWith(new RetryInjectingTx(client), MUST_NOT_BE_CALLED)
                .readQuote(cid, created.quoteId());

        assertThat(read).isEqualTo(created);
        assertThat(read.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.Applied(10_000, 500, 500));
        ResponseEntity<JsonNode> http = getQuote(r.token(), created.quoteId());
        assertThat(http.getStatusCode().value()).isEqualTo(200);
        assertThat(http.getBody().fieldNames()).toIterable().containsExactlyInAnyOrder("quoteId", "cartVersion",
                "addressId", "items", "itemCount", "distinctItemCount", "subtotalPaise", "currency", "createdAt",
                "expiresAt", "benefitPreview", "moneyPreview", "requestId");
    }

    // ---------- legacy absence and corruption ----------

    @Test void a_legacy_quote_without_a_snapshot_reconstructs_and_follows_the_existing_expiry_semantics() {
        Ready r = tenThousand();
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId),
                new Document("$unset", new Document("benefits", "").append("money", "")));

        assertThat(getQuote(r.token(), quoteId).getStatusCode().value()).isEqualTo(200);
        assertThat(quote(r.token(), 1, key, r.address()).getStatusCode().value()).as("replay").isEqualTo(200);
        CheckoutQuote read = serviceWith(new RetryInjectingTx(client), MUST_NOT_BE_CALLED)
                .readQuote(new CustomerId(customerId(r.token())), quoteId);
        assertThat(read.benefitSnapshot()).as("absent is NOT a fabricated NO_BENEFIT").isNull();

        expire(quoteId);
        assertThat(getQuote(r.token(), quoteId).getStatusCode().value()).as("an old expired legacy quote stays 410")
                .isEqualTo(410);
        assertThat(quote(r.token(), 1, key, r.address()).getStatusCode().value()).isEqualTo(410);
    }

    @Test void an_explicit_null_or_empty_or_malformed_stored_snapshot_is_a_safe_500() {
        for (Object bad : new Object[]{null, new Document(), "NO_BENEFIT",
                new Document("outcome", "NO_BENEFIT").append("eligibleSubtotalPaise", 10_000L),
                new Document("outcome", "APPLIED").append("eligibleSubtotalPaise", 10_000L)
                        .append("discountPaise", 0L).append("discountBps", 500)}) {
            Ready r = tenThousand();
            String key = newKey();
            String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
            corrupt(quoteId, new Document("benefits", bad));

            ResponseEntity<JsonNode> read = getQuote(r.token(), quoteId);
            assertThat(read.getStatusCode().value()).as("%s", bad).isEqualTo(500);
            assertThat(read.getBody().get("code").asText()).isEqualTo("INTERNAL");
            assertThat(read.getBody().toString()).doesNotContain("benefits").doesNotContain("outcome");
            assertThat(quote(r.token(), 1, key, r.address()).getStatusCode().value()).as("replay").isEqualTo(500);
        }
    }

    // ---------- the preview is advisory: Order is authoritative ----------

    @Test void a_preview_can_disagree_with_the_authoritative_order_and_the_order_simply_wins() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        com.tazzzo.membership.Membership term = grantMembership(cid);
        CheckoutQuote quote = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, newKey(), r.address(), "req");
        assertThat(quote.benefitSnapshot()).isInstanceOf(CheckoutBenefitSnapshot.Applied.class); // preview: APPLIED
        membershipTermination.revoke(term.membershipId());                                       // then revoked

        com.tazzzo.customer.order.Order order = orderService.placeCodOrder(cid, quote.quoteId());

        // the Order placed normally (no rejection, no new failure reason) and stores ITS authoritative outcome
        assertThat(order.status()).isEqualTo(com.tazzzo.customer.order.OrderStatus.CONFIRMED);
        assertThat(order.benefitSnapshot()).isEqualTo(new com.tazzzo.customer.order.OrderBenefitSnapshot.NoBenefit(
                10_000, com.tazzzo.benefits.BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
        assertThat(CheckoutQuoteRepository.toQuote(storedQuote(quote.quoteId())).benefitSnapshot())
                .as("the quote's advisory preview is untouched by the Order").isEqualTo(
                        new CheckoutBenefitSnapshot.Applied(10_000, 500, 500));
        // the ADVISORY Checkout money (payable 9500) and the AUTHORITATIVE Order money (payable 10000) disagree: fine
        assertThat(quote.moneySnapshot()).isEqualTo(new CheckoutMoneySnapshot(10_000, 500));
        assertThat(CheckoutQuoteRepository.toQuote(storedQuote(quote.quoteId())).moneySnapshot())
                .as("the stored Checkout money is untouched by the Order").isEqualTo(new CheckoutMoneySnapshot(10_000, 500));
        assertThat(order.moneySnapshot()).as("the Order computed ITS OWN authoritative money")
                .isEqualTo(com.tazzzo.customer.order.OrderMoneySnapshot.from(10_000, 0));
        assertThat(order.moneySnapshot().payablePaise()).isEqualTo(10_000);
    }

    // ============================================================
    // PR-19A-1 hardening: the IN-TRANSACTION replay and duplicate-key recovery never evaluate Benefits AGAIN
    // ============================================================

    /** TEST-ONLY seam: hides the stored quote from the PRE-transaction (non-session) idempotency lookup ONLY, so the
     *  request proceeds to its IN-TRANSACTION replay check; records that check finding the durable quote and raises a
     *  flag from that moment on, and counts any insert (there must be none). */
    private static final class FastPathBlindQuotes extends CheckoutQuoteRepository {
        final java.util.concurrent.atomic.AtomicInteger fastPathLookups = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger inTransactionHits = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger inserts = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean replayDecided;

        FastPathBlindQuotes(com.mongodb.client.MongoDatabase db) {
            super(db);
        }

        @Override public Document findByIdempotency(String customerId, String keyDigest) {
            fastPathLookups.incrementAndGet();
            return null; // the "race": the fast path did not see the quote yet
        }

        @Override public Document findByIdempotency(com.mongodb.client.ClientSession session, String customerId,
                                                    String keyDigest) {
            Document found = super.findByIdempotency(session, customerId, keyDigest);
            if (found != null) {
                inTransactionHits.incrementAndGet();
                replayDecided = true;
            }
            return found;
        }

        @Override public void insert(com.mongodb.client.ClientSession session, CheckoutQuote quote, String customerId,
                                     String keyDigest, String fingerprint) {
            inserts.incrementAndGet();
            super.insert(session, quote, customerId, keyDigest, fingerprint);
        }
    }

    @Test void the_in_transaction_replay_returns_the_stored_quote_and_never_evaluates_benefits_again() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        com.tazzzo.membership.Membership term = grantMembership(cid);
        String key = newKey();
        CheckoutQuote winner = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, key, r.address(), "req");
        assertThat(winner.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.Applied(10_000, 500, 500));
        membershipTermination.revoke(term.membershipId()); // a fresh evaluation would now say NO_MEMBERSHIP
        Document quoteBefore = storedQuote(winner.quoteId());
        Document cartBefore = db.getCollection("customer_carts").find(new Document("_id", cid.value())).first();

        FastPathBlindQuotes quotes = new FastPathBlindQuotes(db);
        java.util.concurrent.atomic.AtomicInteger callsBeforeDecision = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger callsAfterDecision = new java.util.concurrent.atomic.AtomicInteger();
        BenefitsEvaluationPort guarded = (c, subtotal) -> {
            if (quotes.replayDecided) {
                callsAfterDecision.incrementAndGet();
                throw new AssertionError("Benefits must NOT be evaluated after the in-transaction replay found the quote");
            }
            callsBeforeDecision.incrementAndGet();
            return realBenefits(benefitRule(10_000, 500)).evaluate(c, subtotal);
        };

        CheckoutQuote result = serviceWith(new RetryInjectingTx(client), quotes, guarded)
                .createQuote(cid, 1, key, r.address(), "req");

        assertThat(quotes.fastPathLookups.get()).as("the pre-transaction lookup ran and missed").isEqualTo(1);
        assertThat(quotes.inTransactionHits.get()).as("the IN-TRANSACTION check found the durable quote").isEqualTo(1);
        assertThat(callsBeforeDecision.get()).as("the candidate's single legitimate pre-transaction evaluation")
                .isEqualTo(1);
        assertThat(callsAfterDecision.get()).as("NO second evaluation after the replay decision").isZero();
        assertThat(result).as("the STORED quote wins over the freshly built candidate").isEqualTo(winner);
        assertThat(result.benefitSnapshot()).as("the stored snapshot, not the candidate's NO_MEMBERSHIP")
                .isEqualTo(new CheckoutBenefitSnapshot.Applied(10_000, 500, 500));
        assertThat(quotes.inserts.get()).as("no quote insert").isZero();
        assertThat(quoteCount(r.token())).isEqualTo(1);
        assertThat(storedQuote(winner.quoteId())).as("the stored quote is untouched").isEqualTo(quoteBefore);
        assertThat(db.getCollection("customer_carts").find(new Document("_id", cid.value())).first())
                .as("no cart mutation").isEqualTo(cartBefore);
    }

    /** TEST-ONLY. Reproduces a lost same-key race: both the pre-transaction and the in-transaction idempotency reads
     *  saw "no quote yet", the insert then fails with a synthetic 11000, and afterwards the reads see the real
     *  collection (what recovery re-reads). Raises a flag the moment the insert fails. */
    private static final class DuplicateKeyOnInsertQuotes extends CheckoutQuoteRepository {
        private volatile boolean hideExisting = true;
        volatile boolean insertFailed;
        final java.util.concurrent.atomic.AtomicInteger recoveryReads = new java.util.concurrent.atomic.AtomicInteger();

        DuplicateKeyOnInsertQuotes(com.mongodb.client.MongoDatabase db) {
            super(db);
        }

        @Override public Document findByIdempotency(String customerId, String keyDigest) {
            if (hideExisting) {
                return null;
            }
            recoveryReads.incrementAndGet();
            return super.findByIdempotency(customerId, keyDigest);
        }

        @Override public Document findByIdempotency(com.mongodb.client.ClientSession session, String customerId,
                                                    String keyDigest) {
            return hideExisting ? null : super.findByIdempotency(session, customerId, keyDigest);
        }

        @Override public void insert(com.mongodb.client.ClientSession session, CheckoutQuote quote, String customerId,
                                     String keyDigest, String fingerprint) {
            hideExisting = false;
            insertFailed = true;
            throw new com.mongodb.MongoWriteException(new com.mongodb.WriteError(11000, "E11000",
                    new org.bson.BsonDocument()), new com.mongodb.ServerAddress());
        }
    }

    @Test void duplicate_key_recovery_returns_the_winners_snapshot_unchanged_and_never_evaluates_benefits_again() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        com.tazzzo.membership.Membership term = grantMembership(cid);
        String key = newKey();
        CheckoutQuote winner = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, key, r.address(), "req");
        assertThat(winner.benefitSnapshot()).isEqualTo(new CheckoutBenefitSnapshot.Applied(10_000, 500, 500));
        membershipTermination.revoke(term.membershipId()); // the loser's own evaluation now differs: NO_MEMBERSHIP
        Document winnerDoc = storedQuote(winner.quoteId());

        DuplicateKeyOnInsertQuotes quotes = new DuplicateKeyOnInsertQuotes(db);
        java.util.concurrent.atomic.AtomicInteger callsBeforeInsert = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger callsInRecovery = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<CheckoutBenefitSnapshot> loserWouldHaveStored =
                new java.util.concurrent.atomic.AtomicReference<>();
        BenefitsEvaluationPort guarded = (c, subtotal) -> {
            if (quotes.insertFailed) {
                callsInRecovery.incrementAndGet();
                throw new AssertionError("Benefits must NOT be evaluated again during duplicate-key recovery");
            }
            callsBeforeInsert.incrementAndGet();
            com.tazzzo.benefits.BenefitEvaluation e = realBenefits(benefitRule(10_000, 500)).evaluate(c, subtotal);
            loserWouldHaveStored.set(CheckoutBenefitSnapshot.from(e, subtotal.paise()));
            return e;
        };

        CheckoutQuote result = serviceWith(new RetryInjectingTx(client), quotes, guarded)
                .createQuote(cid, 1, key, r.address(), "req");

        assertThat(callsBeforeInsert.get()).as("the loser's one legitimate pre-insert evaluation").isEqualTo(1);
        assertThat(loserWouldHaveStored.get()).as("the loser's own preview differs from the winner's")
                .isEqualTo(new CheckoutBenefitSnapshot.NoBenefit(10_000,
                        com.tazzzo.benefits.BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
        assertThat(callsInRecovery.get()).as("NO additional evaluation during recovery").isZero();
        assertThat(quotes.recoveryReads.get()).as("recovery re-read the durable winner").isEqualTo(1);
        assertThat(result).as("the durable WINNER is returned unchanged").isEqualTo(winner);
        assertThat(result.benefitSnapshot()).as("the winner's persisted snapshot, never the loser's")
                .isEqualTo(new CheckoutBenefitSnapshot.Applied(10_000, 500, 500));
        assertThat(storedQuote(winner.quoteId())).as("the winner's document is not replaced or recalculated")
                .isEqualTo(winnerDoc);
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    // ============================================================
    // PR-19A-2 -- the PUBLIC projection of the stored advisory Benefits preview (HTTP level)
    // ============================================================

    private static final String ONLY_NOT_APPLIED = "{\"applied\":false}";

    private static void assertNoInternalBenefitDetail(JsonNode body) {
        assertThat(body.toString()).doesNotContain("NO_MEMBERSHIP").doesNotContain("NO_RULE")
                .doesNotContain("NOT_ELIGIBLE").doesNotContain("reason").doesNotContain("eligible")
                .doesNotContain("membership").doesNotContain("planId").doesNotContain("planVersion")
                .doesNotContain("amountDue").doesNotContain("grandTotal");
    }

    @Test void every_internal_no_benefit_reason_is_publicly_the_identical_applied_false() {
        for (int scenario = 0; scenario < 3; scenario++) {
            Ready r = tenThousand();
            CustomerId cid = new CustomerId(customerId(r.token()));
            BenefitsEvaluationPort port;
            switch (scenario) {
                case 0 -> port = realBenefits(benefitRule(10_000, 500));                       // NO_MEMBERSHIP
                case 1 -> { grantMembership(cid); port = realBenefits(); }                     // NO_RULE
                default -> { grantMembership(cid); port = realBenefits(benefitRule(10_001, 500)); } // NOT_ELIGIBLE
            }
            String key = newKey();
            CheckoutQuote q = serviceWith(new RetryInjectingTx(client), port).createQuote(cid, 1, key, r.address(), "req");
            String internal = ((CheckoutBenefitSnapshot.NoBenefit) q.benefitSnapshot()).reason().name();

            ResponseEntity<JsonNode> get = getQuote(r.token(), q.quoteId());
            ResponseEntity<JsonNode> replay = quote(r.token(), 1, key, r.address());

            assertThat(get.getStatusCode().value()).as(internal).isEqualTo(200);
            assertThat(get.getBody().get("benefitPreview").toString()).as(internal).isEqualTo(ONLY_NOT_APPLIED);
            assertThat(replay.getBody().get("benefitPreview").toString()).as("replay " + internal)
                    .isEqualTo(ONLY_NOT_APPLIED);
            assertNoInternalBenefitDetail(get.getBody());
            assertNoInternalBenefitDetail(replay.getBody());
        }
    }

    @Test void an_applied_preview_projects_the_STORED_discount_and_rate_exactly_and_never_recomputes_them() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        grantMembership(cid);
        CheckoutQuote q = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, newKey(), r.address(), "req");
        // distinguishing stored values: floor(10000 * 123 / 10000) = 123, NOT 777
        db.getCollection("checkout_quotes").updateOne(new Document("_id", q.quoteId()), new Document("$set",
                new Document("benefits", new Document("outcome", "APPLIED").append("eligibleSubtotalPaise", 10_000L)
                        .append("discountPaise", 777L).append("discountBps", 123))
                        .append("money", new Document("merchandiseSubtotalPaise", 10_000L)
                                .append("benefitDiscountPaise", 777L).append("payablePaise", 9_223L))));

        ResponseEntity<JsonNode> res = getQuote(r.token(), q.quoteId());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("benefitPreview").toString())
                .isEqualTo("{\"applied\":true,\"discountPaise\":777,\"discountBps\":123}");
        assertThat(res.getBody().get("moneyPreview").toString()).as("the STORED money, not a rate recomputation")
                .isEqualTo("{\"merchandiseSubtotalPaise\":10000,\"benefitDiscountPaise\":777,\"payablePaise\":9223}");
        assertThat(res.getBody().get("subtotalPaise").asLong()).as("canonical subtotal is untouched").isEqualTo(10_000);
        assertThat(res.getBody().get("items").get(0).get("lineTotalPaise").asLong()).isEqualTo(10_000);
        assertNoInternalBenefitDetail(res.getBody());
    }

    @Test void a_replay_and_a_get_after_a_membership_change_still_show_the_original_applied_preview() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        com.tazzzo.membership.Membership term = grantMembership(cid);
        String key = newKey();
        CheckoutQuote q = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, key, r.address(), "req");
        membershipTermination.revoke(term.membershipId()); // a fresh evaluation would now be NO_MEMBERSHIP -> applied=false

        ResponseEntity<JsonNode> replay = quote(r.token(), 1, key, r.address());
        ResponseEntity<JsonNode> get = getQuote(r.token(), q.quoteId());

        String expected = "{\"applied\":true,\"discountPaise\":500,\"discountBps\":500}";
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("benefitPreview").toString()).as("replay: stored, not re-evaluated")
                .isEqualTo(expected);
        assertThat(get.getBody().get("benefitPreview").toString()).as("GET: stored, not re-evaluated")
                .isEqualTo(expected);
        assertNoInternalBenefitDetail(replay.getBody());
    }

    @Test void a_legacy_quote_omits_benefit_preview_while_every_other_public_field_is_unchanged() {
        Ready r = tenThousand();
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId),
                new Document("$unset", new Document("benefits", "").append("money", "")));

        for (ResponseEntity<JsonNode> res : List.of(getQuote(r.token(), quoteId), quote(r.token(), 1, key, r.address()))) {
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody().has("benefitPreview")).as("absent, NOT a synthesized applied=false").isFalse();
            assertThat(res.getBody().fieldNames()).toIterable().containsExactlyInAnyOrder("quoteId", "cartVersion",
                    "addressId", "items", "itemCount", "distinctItemCount", "subtotalPaise", "currency", "createdAt",
                    "expiresAt", "requestId");
            assertThat(res.getBody().get("subtotalPaise").asLong()).isEqualTo(10_000);
        }

        expire(quoteId);
        assertThat(getQuote(r.token(), quoteId).getStatusCode().value()).as("legacy expired stays 410").isEqualTo(410);
        assertThat(quote(r.token(), 1, key, r.address()).getStatusCode().value()).isEqualTo(410);
    }

    // ============================================================
    // PR-20B -- the ADVISORY Checkout money snapshot / public moneyPreview
    // ============================================================

    private static String moneyJson(long subtotal, long discount, long payable) {
        return "{\"merchandiseSubtotalPaise\":" + subtotal + ",\"benefitDiscountPaise\":" + discount
                + ",\"payablePaise\":" + payable + "}";
    }

    @Test void every_new_quote_persists_money_consistent_with_its_benefits_snapshot_no_benefit_applied_and_full_discount() {
        Object[][] cases = {
                {false, 0, 0, 10_000L, 0L},                                        // NO_MEMBERSHIP -> payable 10000
                {true, 10_000, 500, 10_000L, 500L},                                // APPLIED 500 -> payable 9500
                {true, 10_000, 10_000, 10_000L, 10_000L}};                         // 10000 bps -> payable 0
        for (Object[] c : cases) {
            Ready r = tenThousand();
            CustomerId cid = new CustomerId(customerId(r.token()));
            if ((boolean) c[0]) {
                grantMembership(cid);
            }
            BenefitsEvaluationPort port = (boolean) c[0] ? realBenefits(benefitRule((int) c[1], (int) c[2])) : realBenefits();

            CheckoutQuote q = serviceWith(new RetryInjectingTx(client), port).createQuote(cid, 1, newKey(), r.address(), "req");

            long discount = (long) c[4];
            assertThat(q.moneySnapshot()).isEqualTo(new CheckoutMoneySnapshot(10_000, discount));
            assertThat(q.moneySnapshot().merchandiseSubtotalPaise()).as("the quote's canonical subtotal")
                    .isEqualTo(q.subtotalPaise());
            Document stored = (Document) storedQuote(q.quoteId()).get("money");
            assertThat(stored.keySet()).containsExactlyInAnyOrder("merchandiseSubtotalPaise", "benefitDiscountPaise",
                    "payablePaise");
            assertThat(stored.get("merchandiseSubtotalPaise")).isEqualTo(10_000L);
            assertThat(stored.get("benefitDiscountPaise")).isEqualTo(discount);
            assertThat(stored.get("payablePaise")).isEqualTo(10_000L - discount);
            assertThat(storedQuote(q.quoteId()).keySet()).as("no duplicated top-level money fields")
                    .doesNotContain("payablePaise", "benefitDiscountPaise", "merchandiseSubtotalPaise");
        }
    }

    @Test void the_http_quote_exposes_the_exact_money_preview_beside_the_unchanged_benefit_preview() {
        Ready r = tenThousand();

        ResponseEntity<JsonNode> res = quote(r.token(), 1, newKey(), r.address());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("moneyPreview").toString()).as("zero production rules: subtotal is the payable")
                .isEqualTo(moneyJson(10_000, 0, 10_000));
        assertThat(res.getBody().get("benefitPreview").toString()).isEqualTo(ONLY_NOT_APPLIED);
        assertThat(res.getBody().get("subtotalPaise").asLong()).as("top-level subtotal unchanged").isEqualTo(10_000);
        assertThat(res.getBody().toString().toLowerCase()).doesNotContain("tax").doesNotContain("fee")
                .doesNotContain("coupon").doesNotContain("coin").doesNotContain("wallet").doesNotContain("reason")
                .doesNotContain("membership").doesNotContain("plan").doesNotContain("payment")
                .doesNotContain("amountdue").doesNotContain("grandtotal");
    }

    @Test void a_replay_and_a_get_after_a_membership_change_return_the_original_stored_money_preview() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        com.tazzzo.membership.Membership term = grantMembership(cid);
        String key = newKey();
        CheckoutQuote q = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(10_000, 500)))
                .createQuote(cid, 1, key, r.address(), "req");
        membershipTermination.revoke(term.membershipId()); // a fresh evaluation would now give payable 10000
        Document before = storedQuote(q.quoteId());

        ResponseEntity<JsonNode> replay = quote(r.token(), 1, key, r.address());
        ResponseEntity<JsonNode> get = getQuote(r.token(), q.quoteId());
        CheckoutQuote replayed = serviceWith(new RetryInjectingTx(client), MUST_NOT_BE_CALLED)
                .createQuote(cid, 1, key, r.address(), "req");
        CheckoutQuote read = serviceWith(new RetryInjectingTx(client), MUST_NOT_BE_CALLED).readQuote(cid, q.quoteId());

        assertThat(replay.getBody().get("moneyPreview").toString()).as("replay: stored, not recalculated")
                .isEqualTo(moneyJson(10_000, 500, 9_500));
        assertThat(get.getBody().get("moneyPreview").toString()).as("GET: stored, not recalculated")
                .isEqualTo(moneyJson(10_000, 500, 9_500));
        assertThat(replayed.moneySnapshot()).isEqualTo(q.moneySnapshot());
        assertThat(read.moneySnapshot()).isEqualTo(q.moneySnapshot());
        assertThat(storedQuote(q.quoteId())).as("the stored quote is untouched").isEqualTo(before);
        assertThat(quoteCount(r.token())).isEqualTo(1);
    }

    @Test void a_replay_ignores_a_benefits_configuration_change_for_the_money_preview_too() {
        Ready r = tenThousand();
        CustomerId cid = new CustomerId(customerId(r.token()));
        grantMembership(cid);
        String key = newKey();
        CheckoutQuote first = serviceWith(new RetryInjectingTx(client), realBenefits())
                .createQuote(cid, 1, key, r.address(), "req");
        assertThat(first.moneySnapshot()).isEqualTo(new CheckoutMoneySnapshot(10_000, 0));

        CheckoutQuote replay = serviceWith(new RetryInjectingTx(client), realBenefits(benefitRule(1_000, 9_000)))
                .createQuote(cid, 1, key, r.address(), "req");

        assertThat(replay).isEqualTo(first);
        assertThat(replay.moneySnapshot()).isEqualTo(new CheckoutMoneySnapshot(10_000, 0));
    }

    @Test void a_quote_with_benefits_but_no_money_is_a_valid_legacy_quote_with_no_money_preview() {
        Ready r = tenThousand();
        String key = newKey();
        String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId),
                new Document("$unset", new Document("money", "")));

        for (ResponseEntity<JsonNode> res : List.of(getQuote(r.token(), quoteId), quote(r.token(), 1, key, r.address()))) {
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody().has("moneyPreview")).as("absent, NOT payable = subtotal and NOT 0").isFalse();
            assertThat(res.getBody().get("benefitPreview").toString()).as("Benefits preview unaffected")
                    .isEqualTo(ONLY_NOT_APPLIED);
            assertThat(res.getBody().get("subtotalPaise").asLong()).isEqualTo(10_000);
        }
        CheckoutQuote read = serviceWith(new RetryInjectingTx(client), MUST_NOT_BE_CALLED)
                .readQuote(new CustomerId(customerId(r.token())), quoteId);
        assertThat(read.moneySnapshot()).as("absent is NOT a fabricated money snapshot").isNull();

        expire(quoteId);
        assertThat(getQuote(r.token(), quoteId).getStatusCode().value()).as("an old expired quote stays 410").isEqualTo(410);
    }

    @Test void a_stored_money_that_is_corrupt_or_without_its_benefits_is_a_safe_500() {
        Object[] bad = {null, new Document(), "9500", new Document("payablePaise", 1L),
                new Document("merchandiseSubtotalPaise", 10_000L).append("benefitDiscountPaise", 0L)
                        .append("payablePaise", 0L),                                            // formula mismatch
                new Document("merchandiseSubtotalPaise", 10_000L).append("benefitDiscountPaise", 500L)
                        .append("payablePaise", 9_500L)};                                       // disagrees with benefits (NO_BENEFIT)
        for (Object money : bad) {
            Ready r = tenThousand();
            String key = newKey();
            String quoteId = quote(r.token(), 1, key, r.address()).getBody().get("quoteId").asText();
            corrupt(quoteId, new Document("money", money));

            ResponseEntity<JsonNode> read = getQuote(r.token(), quoteId);
            assertThat(read.getStatusCode().value()).as("%s", money).isEqualTo(500);
            assertThat(read.getBody().get("code").asText()).isEqualTo("INTERNAL");
            assertThat(read.getBody().toString()).doesNotContain("money").doesNotContain("payable");
            assertThat(quote(r.token(), 1, key, r.address()).getStatusCode().value()).as("replay").isEqualTo(500);
        }
        // modern money but the Benefits snapshot dropped: invalid, never repaired
        Ready r = tenThousand();
        String quoteId = quote(r.token(), 1, newKey(), r.address()).getBody().get("quoteId").asText();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", quoteId),
                new Document("$unset", new Document("benefits", "")));
        assertThat(getQuote(r.token(), quoteId).getStatusCode().value()).isEqualTo(500);
    }
}
