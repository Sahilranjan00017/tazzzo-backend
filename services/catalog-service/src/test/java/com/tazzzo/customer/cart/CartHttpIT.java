package com.tazzzo.customer.cart;

import com.tazzzo.common.audit.TestActors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.money.Currency;
import com.tazzzo.customer.address.AddressLimitProperties;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12C — the customer cart over real HTTP, real customer auth, real Mongo and the REAL commerce
 * composition (catalog eligibility, pricing, inventory, serviceability). Only the address ->
 * location seam is exercised end to end via saved addresses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CartHttpIT.TestBeans.class})
class CartHttpIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));
    static final String VERTICAL = "TZV-000001";
    static final String PIN_OK = "560201";
    static final String PIN_NO = "560299";
    static final ObjectMapper JSON = new ObjectMapper();

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @DynamicPropertySource
    static void sessionProps(DynamicPropertyRegistry r) {
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
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired ServiceabilityService serviceability;
    @Autowired PricingService pricing;
    @Autowired InventoryService inventory;
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService changes;
    @Autowired MeterRegistry registry;

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand(PIN_OK, "SA-CART-1",
                List.of(new ServiceabilityRoute("FUL-CART-INTERNAL", 0, true)), "seed", null));
    }

    // ---------- fixtures ----------

    private int skuSeq = 0;

    /** A fresh eligible, priced SKU (selling 10000 paise) stocked at the serviceable location. */
    private String sku(long sellingPaise, int onHand) {
        String id = "TZP-9" + String.format("%05d", ++skuSeq) + (System.nanoTime() % 1000);
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
        inventory.setInventory(new SetInventoryCommand(id, "FUL-CART-INTERNAL", onHand, 0, 10, "seed", null));
        return id;
    }

    private String token() {
        String phone = "+9198" + String.format("%08d", (System.nanoTime() / 7) % 100_000_000);
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody()
                .get("accessToken").asText();
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

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, String ifMatch, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        if (ifMatch != null) h.set("If-Match", ifMatch);
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> cart(String token) {
        return call(HttpMethod.GET, "/v1/customer/cart", token, null, null);
    }

    private ResponseEntity<JsonNode> put(String token, String sku, int qty, String ifMatch) {
        return call(HttpMethod.PUT, "/v1/customer/cart/items/" + sku, token, ifMatch, Map.of("quantity", qty));
    }

    private static String tag(long version) {
        return "\"cart-" + version + "\"";
    }

    private static JsonNode item(JsonNode cart, String sku) {
        for (JsonNode i : cart.get("items")) if (i.get("skuId").asText().equals(sku)) return i;
        throw new AssertionError("no item " + sku + " in " + cart);
    }

    private static List<String> issues(JsonNode item) {
        List<String> out = new ArrayList<>();
        item.get("issues").forEach(n -> out.add(n.asText()));
        return out;
    }

    private double count(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ---------- auth ----------

    @Test void every_cart_route_requires_customer_authentication() {
        assertThat(call(HttpMethod.GET, "/v1/customer/cart", null, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/TZP-1", null, tag(0), Map.of("quantity", 1))
                .getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.DELETE, "/v1/customer/cart/items/TZP-1", null, tag(0), null)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.DELETE, "/v1/customer/cart", null, tag(0), null).getStatusCode().value())
                .isEqualTo(401);
    }

    // ---------- empty / lifecycle ----------

    @Test void empty_cart_is_logical_version_zero_with_etag_and_no_store_and_creates_no_document() {
        String t = token();
        ResponseEntity<JsonNode> res = cart(t);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getETag()).isEqualTo(tag(0));
        assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode b = res.getBody();
        assertThat(b.get("version").asLong()).isZero();
        assertThat(b.get("items")).isEmpty();
        assertThat(b.get("itemCount").asInt()).isZero();
        assertThat(b.get("distinctItemCount").asInt()).isZero();
        assertThat(b.get("subtotalPaise").asLong()).isZero();
        assertThat(b.get("requestId").asText()).isNotBlank();
        long before = db.getCollection("customer_carts").countDocuments();
        cart(t);
        assertThat(db.getCollection("customer_carts").countDocuments()).isEqualTo(before);
    }

    @Test void full_lifecycle_put_put_delete_item_clear_with_etags_and_never_persists_client_values() {
        String t = token();
        String a = sku(10000, 50);
        String b = sku(2500, 50);
        ResponseEntity<JsonNode> r1 = call(HttpMethod.PUT, "/v1/customer/cart/items/" + a, t, tag(0),
                Map.of("quantity", 2, "price", 1, "unitPricePaise", 1, "title", "evil", "stockState", "IN_STOCK",
                        "customerId", "CUS_someoneelse", "fulfillmentLocationId", "X"));
        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        assertThat(r1.getHeaders().getETag()).isEqualTo(tag(1));
        assertThat(item(r1.getBody(), a).get("price").get("unitPricePaise").asLong()).isEqualTo(10000);

        ResponseEntity<JsonNode> r2 = put(t, b, 3, tag(1));
        assertThat(r2.getHeaders().getETag()).isEqualTo(tag(2));
        assertThat(r2.getBody().get("itemCount").asInt()).isEqualTo(5);
        assertThat(r2.getBody().get("distinctItemCount").asInt()).isEqualTo(2);
        assertThat(r2.getBody().get("subtotalPaise").asLong()).isEqualTo(2 * 10000 + 3 * 2500);

        // storage shape: only intent — exactly these keys, keyed by the verified customer.
        Document stored = null;
        for (Document d : db.getCollection("customer_carts").find()) {
            if (d.getList("items", Document.class).stream().anyMatch(i -> a.equals(i.getString("skuId")))) stored = d;
        }
        assertThat(stored).isNotNull();
        assertThat(stored.keySet()).containsExactlyInAnyOrder("_id", "items", "version", "createdAt", "updatedAt",
                "expiresAt");
        assertThat(stored.getString("_id")).isNotEqualTo("CUS_someoneelse");
        for (Document i : stored.getList("items", Document.class)) {
            // intent + the price OBSERVED when the line was set (used only for PRICE_CHANGED in the revalidate band)
            assertThat(i.keySet()).containsExactlyInAnyOrder("skuId", "quantity", "addedAt", "updatedAt",
                    "unitPricePaiseAtUpdate");
        }

        ResponseEntity<JsonNode> r3 = call(HttpMethod.DELETE, "/v1/customer/cart/items/" + a, t, tag(2), null);
        assertThat(r3.getStatusCode().value()).isEqualTo(200);
        assertThat(r3.getHeaders().getETag()).isEqualTo(tag(3));
        assertThat(r3.getBody().get("distinctItemCount").asInt()).isEqualTo(1);

        ResponseEntity<JsonNode> r4 = call(HttpMethod.DELETE, "/v1/customer/cart", t, tag(3), null);
        assertThat(r4.getStatusCode().value()).isEqualTo(200);
        assertThat(r4.getHeaders().getETag()).isEqualTo(tag(4));
        assertThat(r4.getBody().get("items")).isEmpty();
        assertThat(cart(t).getHeaders().getETag()).as("cleared cart keeps its history").isEqualTo(tag(4));
    }

    @Test void clearing_a_cart_that_never_existed_is_a_deterministic_noop_at_version_zero() {
        String t = token();
        ResponseEntity<JsonNode> r = call(HttpMethod.DELETE, "/v1/customer/cart", t, tag(0), null);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getETag()).isEqualTo(tag(0));
    }

    @Test void removing_an_absent_line_is_404() {
        String t = token();
        String a = sku(1000, 5);
        put(t, a, 1, tag(0));
        assertThat(call(HttpMethod.DELETE, "/v1/customer/cart/items/TZP-1", t, tag(1), null).getStatusCode().value())
                .isEqualTo(404);
    }

    // ---------- validation / preconditions ----------

    @Test void if_match_missing_is_428_malformed_is_400_stale_is_412() {
        String t = token();
        String a = sku(1000, 5);
        assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/" + a, t, null, Map.of("quantity", 1))
                .getStatusCode().value()).isEqualTo(428);
        for (String bad : List.of("garbage", "\"cart-\"", "\"cart-abc\"", "cart-1x", "\"cart-" + "9".repeat(30) + "\"",
                "W/\"cart-1\"", "*")) {
            ResponseEntity<JsonNode> r = call(HttpMethod.PUT, "/v1/customer/cart/items/" + a, t, bad,
                    Map.of("quantity", 1));
            assertThat(r.getStatusCode().value()).as(bad).isEqualTo(400);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(put(t, a, 1, tag(5)).getStatusCode().value()).isEqualTo(412);
        assertThat(call(HttpMethod.DELETE, "/v1/customer/cart", t, null, null).getStatusCode().value()).isEqualTo(428);
        assertThat(put(t, a, 1, tag(0)).getStatusCode().value()).isEqualTo(200);
        assertThat(put(t, a, 2, tag(0)).getStatusCode().value()).as("stale").isEqualTo(412);
        assertThat(put(t, a, 2, "cart-1").getStatusCode().value()).as("unquoted form accepted").isEqualTo(200);
    }

    @Test void quantity_validation_is_400_and_the_sku_grammar_and_unknown_sku_are_404() {
        String t = token();
        String a = sku(1000, 50);
        assertThat(put(t, a, 0, tag(0)).getStatusCode().value()).isEqualTo(400);
        assertThat(put(t, a, -3, tag(0)).getStatusCode().value()).isEqualTo(400);
        assertThat(put(t, a, 21, tag(0)).getStatusCode().value()).isEqualTo(400);
        for (Object bad : new Object[]{Map.of("quantity", "2"), Map.of("quantity", 1.5), Map.of("x", 1),
                Map.of("quantity", 99999999999L), List.of(1)}) {
            assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/" + a, t, tag(0), bad).getStatusCode().value())
                    .as(String.valueOf(bad)).isEqualTo(400);
        }
        assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/" + a, t, tag(0), null).getStatusCode().value())
                .isEqualTo(400);
        ResponseEntity<JsonNode> unknown = put(t, "TZP-999999999", 1, tag(0));
        ResponseEntity<JsonNode> malformed = put(t, "not-a-sku", 1, tag(0));
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(malformed.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody().get("code").asText()).isEqualTo(malformed.getBody().get("code").asText());
        assertThat(put(t, a, 20, tag(0)).getStatusCode().value()).isEqualTo(200);
        assertThat(cart(t).getBody().get("version").asLong()).isEqualTo(1);
    }

    @Test void the_fifty_first_distinct_sku_is_409_but_updating_an_existing_line_still_works() {
        String t = token();
        List<String> skus = new ArrayList<>();
        for (int i = 0; i < 51; i++) skus.add(sku(500, 5));
        long v = 0;
        for (int i = 0; i < 50; i++) {
            ResponseEntity<JsonNode> r = put(t, skus.get(i), 1, tag(v));
            assertThat(r.getStatusCode().value()).as("sku " + i).isEqualTo(200);
            v = r.getBody().get("version").asLong();
        }
        ResponseEntity<JsonNode> over = put(t, skus.get(50), 1, tag(v));
        assertThat(over.getStatusCode().value()).isEqualTo(409);
        assertThat(over.getBody().get("code").asText()).isEqualTo("CART_ITEM_LIMIT_REACHED");
        assertThat(put(t, skus.get(0), 7, tag(v)).getStatusCode().value()).isEqualTo(200);
    }

    // ---------- ownership ----------

    @Test void carts_are_isolated_per_customer() {
        String a = token();
        String b = token();
        String s = sku(1000, 5);
        put(a, s, 2, tag(0));
        assertThat(cart(b).getBody().get("items")).isEmpty();
        assertThat(cart(b).getBody().get("version").asLong()).isZero();
        assertThat(cart(a).getBody().get("itemCount").asInt()).isEqualTo(2);
    }

    // ---------- commerce truth ----------

    @Test void without_location_stock_is_unknown_serviceable_is_null_and_nothing_is_buyable() {
        String t = token();
        String s = sku(10000, 50);
        JsonNode i = item(put(t, s, 2, tag(0)).getBody(), s);
        assertThat(i.get("availability").get("stockState").asText()).isEqualTo("UNKNOWN");
        assertThat(i.get("availability").get("serviceable").isNull()).isTrue();
        assertThat(i.get("buyable").asBoolean()).isFalse();
        assertThat(issues(i)).containsExactly("LOCATION_REQUIRED");
        assertThat(i.get("lineTotalPaise").asLong()).as("price is location-independent").isEqualTo(20000);
    }

    @Test void with_a_serviceable_address_and_stock_the_line_is_buyable() {
        String t = token();
        String addr = address(t, PIN_OK);
        String s = sku(10000, 50);
        put(t, s, 2, tag(0));
        JsonNode body = call(HttpMethod.GET, "/v1/customer/cart?addressId=" + addr, t, null, null).getBody();
        JsonNode i = item(body, s);
        assertThat(i.get("availability").get("serviceable").asBoolean()).isTrue();
        assertThat(i.get("availability").get("stockState").asText()).isEqualTo("IN_STOCK");
        assertThat(i.get("buyable").asBoolean()).isTrue();
        assertThat(issues(i)).isEmpty();
        assertThat(body.get("subtotalPaise").asLong()).isEqualTo(20000);
    }

    @Test void mutation_responses_also_accept_the_location_context() {
        String t = token();
        String addr = address(t, PIN_OK);
        String s = sku(3000, 50);
        JsonNode i = item(call(HttpMethod.PUT, "/v1/customer/cart/items/" + s + "?addressId=" + addr, t, tag(0),
                Map.of("quantity", 1)).getBody(), s);
        assertThat(i.get("buyable").asBoolean()).isTrue();
    }

    @Test void an_unserviceable_address_flags_the_line_but_keeps_it_in_the_cart() {
        String t = token();
        String addr = address(t, PIN_NO);
        String s = sku(10000, 50);
        put(t, s, 1, tag(0));
        JsonNode i = item(call(HttpMethod.GET, "/v1/customer/cart?addressId=" + addr, t, null, null).getBody(), s);
        assertThat(i.get("availability").get("serviceable").asBoolean()).isFalse();
        assertThat(i.get("buyable").asBoolean()).isFalse();
        assertThat(issues(i)).containsExactly("UNSERVICEABLE");
    }

    @Test void out_of_stock_stays_in_the_cart_readable_and_not_buyable_without_quantity_reduction() {
        String t = token();
        String addr = address(t, PIN_OK);
        String s = sku(10000, 0);
        assertThat(put(t, s, 4, tag(0)).getStatusCode().value()).as("out-of-stock SKU may be added").isEqualTo(200);
        JsonNode i = item(call(HttpMethod.GET, "/v1/customer/cart?addressId=" + addr, t, null, null).getBody(), s);
        assertThat(i.get("quantity").asInt()).isEqualTo(4);
        assertThat(i.get("buyable").asBoolean()).isFalse();
        assertThat(i.get("availability").get("stockState").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(issues(i)).containsExactly("OUT_OF_STOCK");
    }

    @Test void requested_quantity_above_available_is_insufficient_stock_never_silently_reduced() {
        String t = token();
        String addr = address(t, PIN_OK);
        String s = sku(10000, 3);
        put(t, s, 5, tag(0));
        JsonNode i = item(call(HttpMethod.GET, "/v1/customer/cart?addressId=" + addr, t, null, null).getBody(), s);
        assertThat(i.get("quantity").asInt()).isEqualTo(5);
        assertThat(i.get("buyable").asBoolean()).isFalse();
        assertThat(issues(i)).containsExactly("INSUFFICIENT_STOCK");
    }

    /** The stored cart holding {@code sku} (each test uses fresh SKUs, so this is exactly one customer's cart). */
    private Document storedCartWith(String sku) {
        return db.getCollection("customer_carts").find(new Document("items.skuId", sku)).first();
    }

    /** Ages a stored cart without moving the server clock (which would also expire the access token). */
    private void age(String sku, java.time.Duration age) {
        Instant last = Instant.now().minus(age);
        db.getCollection("customer_carts").updateOne(new Document("_id", storedCartWith(sku).getString("_id")),
                new Document("$set", new Document("updatedAt", Date.from(last))
                        .append("expiresAt", Date.from(last.plus(java.time.Duration.ofDays(7))))));
    }

    @Test void an_aging_cart_is_revalidated_against_current_price_product_and_stock_without_being_rewritten() {
        String t = token();
        String addr = address(t, PIN_OK);
        String moved = sku(10000, 50);
        String gone = sku(2000, 50);
        String empty = sku(3000, 50);
        String steady = sku(4000, 50);
        put(t, moved, 1, tag(0));
        put(t, gone, 1, tag(1));
        put(t, empty, 1, tag(2));
        put(t, steady, 1, tag(3));
        // FRESH: a moved price is simply the current price, no PRICE_CHANGED
        pricing.upsertPrice(new UpsertPriceCommand(moved, 12000, 15000, Currency.INR, null, null, "seed", 1L));
        JsonNode fresh = call(HttpMethod.GET, "/v1/customer/cart?addressId=" + addr, t, null, null).getBody();
        assertThat(fresh.get("freshness").asText()).isEqualTo("FRESH");
        assertThat(issues(item(fresh, moved))).isEmpty();

        age(moved, java.time.Duration.ofDays(3));
        db.getCollection("products").updateOne(new Document("_id", gone),
                new Document("$set", new Document("lifecycle", "draft")));
        db.getCollection("inventory").updateOne(new Document("sku_id", empty).append("fulfillment_location_id", "FUL-CART-INTERNAL"),
                new Document("$set", new Document("on_hand", 0)));
        Document before = storedCartWith(moved);

        ResponseEntity<JsonNode> res = call(HttpMethod.GET, "/v1/customer/cart?addressId=" + addr, t, null, null);
        JsonNode body = res.getBody();
        assertThat(body.get("freshness").asText()).isEqualTo("REVALIDATE");
        assertThat(issues(item(body, moved))).containsExactly("PRICE_CHANGED");
        assertThat(item(body, moved).get("buyable").asBoolean()).as("priced at the current price").isTrue();
        assertThat(item(body, moved).get("price").get("unitPricePaise").asLong()).isEqualTo(12000);
        assertThat(issues(item(body, gone))).contains("PRODUCT_UNAVAILABLE");
        assertThat(issues(item(body, empty))).containsExactly("OUT_OF_STOCK");
        assertThat(issues(item(body, steady))).isEmpty();
        assertThat(body.get("version").asLong()).isEqualTo(4);
        assertThat(res.getHeaders().getETag()).isEqualTo(tag(4));
        assertThat(storedCartWith(moved)).as("a revalidate read writes nothing").isEqualTo(before);

        // touching the moved line records the new observation and makes the cart FRESH again
        JsonNode after = put(t, moved, 2, tag(4)).getBody();
        assertThat(after.get("freshness").asText()).isEqualTo("FRESH");
        assertThat(issues(item(after, moved))).doesNotContain("PRICE_CHANGED");
    }

    @Test void a_price_change_is_reflected_on_the_next_read_and_not_locked_into_the_cart() {
        String t = token();
        String s = sku(10000, 50);
        put(t, s, 3, tag(0));
        pricing.upsertPrice(new UpsertPriceCommand(s, 7000, 9000, Currency.INR, null, null, "seed", 1L));
        JsonNode body = cart(t).getBody();
        assertThat(item(body, s).get("price").get("unitPricePaise").asLong()).isEqualTo(7000);
        assertThat(body.get("subtotalPaise").asLong()).isEqualTo(21000);
    }

    @Test void a_sku_that_becomes_ineligible_is_still_readable_with_a_bounded_issue_and_removable_but_not_addable() {
        String t = token();
        String keep = sku(1000, 5);
        String gone = sku(2000, 5);
        put(t, keep, 1, tag(0));
        put(t, gone, 2, tag(1));
        db.getCollection("products").updateOne(new Document("_id", gone),
                new Document("$set", new Document("lifecycle", "draft")));
        ResponseEntity<JsonNode> read = cart(t);
        assertThat(read.getStatusCode().value()).isEqualTo(200);
        JsonNode i = item(read.getBody(), gone);
        assertThat(issues(i)).contains("PRODUCT_UNAVAILABLE");
        assertThat(i.get("buyable").asBoolean()).isFalse();
        assertThat(i.get("lineTotalPaise").isNull()).isTrue();
        assertThat(item(read.getBody(), keep).get("issues")).isNotNull();
        assertThat(read.getBody().get("subtotalPaise").asLong()).as("excludes the unavailable line").isEqualTo(1000);
        assertThat(put(t, gone, 3, tag(2)).getStatusCode().value()).as("cannot be newly added or changed").isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/v1/customer/cart/items/" + gone, t, tag(2), null).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test void foreign_and_unknown_address_ids_are_indistinguishable_404s() {
        String owner = token();
        String other = token();
        String foreign = address(owner, PIN_OK);
        ResponseEntity<JsonNode> f = call(HttpMethod.GET, "/v1/customer/cart?addressId=" + foreign, other, null, null);
        ResponseEntity<JsonNode> u = call(HttpMethod.GET, "/v1/customer/cart?addressId=ADDR_doesnotexist000", other,
                null, null);
        ResponseEntity<JsonNode> m = call(HttpMethod.GET, "/v1/customer/cart?addressId=%20junk", other, null, null);
        assertThat(f.getStatusCode().value()).isEqualTo(404);
        assertThat(u.getStatusCode().value()).isEqualTo(404);
        assertThat(m.getStatusCode().value()).isEqualTo(404);
        assertThat(f.getBody().get("code").asText()).isEqualTo(u.getBody().get("code").asText());
        assertThat(f.getBody().get("message").asText()).isEqualTo(u.getBody().get("message").asText());
    }

    @Test void responses_never_leak_fulfillment_service_area_or_stock_internals_or_pii() {
        String t = token();
        String addr = address(t, PIN_OK);
        String s = sku(10000, 50);
        put(t, s, 1, tag(0));
        String raw = call(HttpMethod.GET, "/v1/customer/cart?addressId=" + addr, t, null, null).getBody().toString();
        assertThat(raw).doesNotContain("FUL-CART-INTERNAL").doesNotContain("fulfillmentLocationId")
                .doesNotContain("SA-CART-1").doesNotContain("serviceAreaId").doesNotContain("warehouse")
                .doesNotContain("onHand").doesNotContain("reserved").doesNotContain("CUS_")
                .doesNotContain("560201").doesNotContain(addr);
    }

    // ---------- concurrency ----------

    @Test void concurrent_http_mutations_at_the_same_etag_yield_exactly_one_success() throws Exception {
        String t = token();
        String a = sku(1000, 50);
        String b = sku(1000, 50);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 = pool.submit(() -> { go.await(); return put(t, a, 1, tag(0)).getStatusCode().value(); });
            Future<Integer> f2 = pool.submit(() -> { go.await(); return put(t, b, 1, tag(0)).getStatusCode().value(); });
            go.countDown();
            List<Integer> statuses = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 412);
        } finally {
            pool.shutdownNow();
        }
        assertThat(cart(t).getBody().get("version").asLong()).isEqualTo(1);
    }

    // ---------- identity integrity ----------

    @Test void a_session_whose_customer_identity_is_gone_is_refused_and_no_cart_is_created() {
        String t = token();
        String a = sku(1000, 5);
        long carts = db.getCollection("customer_carts").countDocuments();
        db.getCollection("customers").deleteMany(new Document());
        ResponseEntity<JsonNode> r = put(t, a, 1, tag(0));
        assertThat(r.getStatusCode().value()).isIn(401, 503);
        assertThat(db.getCollection("customer_carts").countDocuments()).isEqualTo(carts);
    }

    // ---------- observability ----------

    @Test void metrics_are_bounded_counted_once_and_carry_no_identifiers() {
        String t = token();
        String s = sku(1000, 5);
        double readBefore = count("customer_cart_read_success");
        double setBefore = count("customer_cart_mutation_success", "operation", "set_item");
        double staleBefore = count("customer_cart_failure", "operation", "set_item", "reason", "precondition_failed");
        double locBefore = count("customer_cart_item_issue", "issue", "location_required");

        put(t, s, 1, tag(0));
        cart(t);
        put(t, s, 2, tag(0)); // stale

        assertThat(count("customer_cart_mutation_success", "operation", "set_item") - setBefore).isEqualTo(1);
        assertThat(count("customer_cart_read_success") - readBefore).isEqualTo(1);
        assertThat(count("customer_cart_failure", "operation", "set_item", "reason", "precondition_failed")
                - staleBefore).as("failure counted exactly once").isEqualTo(1);
        assertThat(count("customer_cart_item_issue", "issue", "location_required") - locBefore).isGreaterThanOrEqualTo(2);

        for (Meter m : registry.getMeters()) {
            if (!m.getId().getName().startsWith("customer_cart") && !m.getId().getName().equals("cart_expired")) continue;
            for (Tag tg : m.getId().getTags()) {
                assertThat(tg.getValue()).doesNotContain("TZP-").doesNotContain("CUS_").doesNotContain("ADDR_")
                        .doesNotContain("SES_");
                assertThat(tg.getKey()).isIn("operation", "reason", "issue");
            }
        }
    }

    @Test void a_rejected_request_records_no_success_metric() {
        String t = token();
        double before = count("customer_cart_mutation_success");
        put(t, "TZP-999999998", 1, tag(0)); // 404
        put(t, "TZP-999999998", 0, tag(0)); // 400
        assertThat(count("customer_cart_mutation_success")).isEqualTo(before);
    }

    @Test void wrong_content_type_is_a_safe_415_never_a_500_and_is_counted_exactly_once() {
        String t = token();
        String s = sku(1000, 5);
        double mediaBefore = count("customer_cart_failure", "operation", "set_item", "reason", "unsupported_media_type");
        double internalBefore = count("customer_cart_failure", "reason", "internal");
        double allBefore = count("customer_cart_failure");
        double successBefore = count("customer_cart_mutation_success");

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        h.setBearerAuth(t);
        h.set("If-Match", tag(0));
        ResponseEntity<JsonNode> r = rest.exchange(url("/v1/customer/cart/items/" + s), HttpMethod.PUT,
                new HttpEntity<>("quantity=1", h), JsonNode.class);

        assertThat(r.getStatusCode().value()).isEqualTo(415);
        assertThat(r.getBody().get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(r.getBody().get("requestId").asText()).isNotBlank();
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(r.getBody().toString()).doesNotContain("HttpMediaType").doesNotContain("springframework")
                .doesNotContain("text/plain").doesNotContain("Exception").doesNotContain("application/json");
        assertThat(count("customer_cart_failure", "operation", "set_item", "reason", "unsupported_media_type")
                - mediaBefore).as("counted exactly once").isEqualTo(1);
        assertThat(count("customer_cart_failure") - allBefore).as("no second failure series bumped").isEqualTo(1);
        assertThat(count("customer_cart_failure", "reason", "internal")).isEqualTo(internalBefore);
        assertThat(count("customer_cart_mutation_success")).isEqualTo(successBefore);
        assertThat(cart(t).getBody().get("version").asLong()).as("nothing mutated").isZero();
    }
}
