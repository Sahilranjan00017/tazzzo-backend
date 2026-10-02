package com.tazzzo.customer.order;

import com.tazzzo.common.audit.TestActors;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.money.Currency;
import com.tazzzo.customer.address.AddressLimitProperties;
import com.tazzzo.inventory.InventoryReservation;
import com.tazzzo.inventory.InventoryReservationFailure;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.inventory.InventoryReservationObservability;
import com.tazzzo.inventory.InventoryReservationProperties;
import com.tazzzo.inventory.InventoryReservationRepository;
import com.tazzzo.inventory.InventoryReservationService;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-15A-2 — the customer Order HTTP surface, end to end: real customer auth, real cart, real checkout
 * quote, real COD placement, real Mongo. Raw JSON is inspected for leakage; committed state (stock,
 * reservation, cart, orders) is asserted, never just the status code. Failure injection is a test-only
 * {@code @Primary} repository / reservation service switched by {@link #MODE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, OrderHttpIT.TestBeans.class})
class OrderHttpIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));
    static final String VERTICAL = "TZV-000001";
    static final String LOC = "FUL-ORD-INTERNAL";

    enum Mode { NORMAL, OUTAGE, DEFECT, RESERVATION_EXPIRED }

    static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.NORMAL);

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
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

        @Bean @Primary
        OrderRepository faultyOrders(MongoDatabase database) {
            return new OrderRepository(database) {
                private void maybeFail() {
                    switch (MODE.get()) {
                        case OUTAGE -> throw new MongoException("simulated outage host=10.0.0.1 secret-detail");
                        case DEFECT -> throw new IllegalStateException("secret-internal-detail pii@example.com");
                        default -> { }
                    }
                }
                @Override public Document findByCustomerAndQuote(String customerId, String quoteId) {
                    maybeFail();
                    return super.findByCustomerAndQuote(customerId, quoteId);
                }
                @Override public Document findOwnedById(String orderId, String customerId) {
                    maybeFail();
                    return super.findOwnedById(orderId, customerId);
                }
            };
        }

        @Bean @Primary
        InventoryReservationService expiringReservations(InventoryService inventory,
                                                          InventoryReservationRepository repo,
                                                          InventoryReservationProperties props,
                                                          InventoryReservationObservability obs, Clock clock, Tx tx) {
            return new InventoryReservationService(inventory, repo, props, obs, clock, tx) {
                @Override
                public InventoryReservation consume(com.mongodb.client.ClientSession s, InventoryReservationId id) {
                    if (MODE.get() == Mode.RESERVATION_EXPIRED) {
                        throw new InventoryReservationFailure(InventoryReservationFailure.Reason.RESERVATION_EXPIRED,
                                "secret reservation " + id.value() + " at " + LOC);
                    }
                    return super.consume(s, id);
                }
            };
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired ServiceabilityService serviceability;
    @Autowired PricingService pricing;
    @Autowired InventoryService inventory;
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService changes;
    @Autowired MeterRegistry registry;
    @Autowired OrderService orderService;

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
    }

    @BeforeEach
    void normal() {
        MODE.set(Mode.NORMAL);
    }

    // ---------- fixtures ----------

    private int seq = 0;

    private record Shopper(String token, String customerId, String sku, String addressId, String quoteId, String pin) { }

    private String sku(long sellingPaise, int onHand) {
        String id = "TZP-8" + String.format("%05d", ++seq) + (System.nanoTime() % 1000);
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
        inventory.setInventory(new SetInventoryCommand(id, LOC, onHand, 0, 10, "seed", null));
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

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Map<String, String> extra,
                                          Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        if (extra != null) extra.forEach(h::set);
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> quote(String token, String addressId, long cartVersion) {
        return call(HttpMethod.POST, "/v1/customer/checkout/quote", token,
                Map.of("If-Match", "\"cart-" + cartVersion + "\"", "Idempotency-Key", "idem-" + java.util.UUID.randomUUID()),
                Map.of("addressId", addressId));
    }

    /** A customer with a cart (2 x 5000 paise, stock 10), one saved address in its own serviceable PIN, and
     *  one fresh quote. Everything is unique per call so tests can mutate freely. */
    private Shopper shopper() {
        return shopper(true);
    }

    private Shopper shopper(boolean withCoordinates) {
        String pin = String.format("5603%02d", ++seq % 100);
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand(pin, "SA-ORD-" + seq,
                List.of(new ServiceabilityRoute(LOC, 0, true)), "seed", null));
        String sku = sku(5000, 10);
        String token = token();
        assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/" + sku, token, Map.of("If-Match", "\"cart-0\""),
                Map.of("quantity", 2)).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> a = new HashMap<>();
        a.put("label", "HOME");
        a.put("recipientName", "Ravi Kumar");
        a.put("recipientPhone", "+919876500001");
        a.put("addressLine1", "12 MG Road");
        a.put("addressLine2", "Flat 4B");
        a.put("landmark", "Near Park");
        a.put("city", "Bengaluru");
        a.put("state", "Karnataka");
        a.put("postalCode", pin);
        // coordinates are OPTIONAL in the address API (both absent, or both present): a coordinate-less saved
        // address is valid and the Order snapshot preserves that, so the fixture can go either way.
        if (withCoordinates) {
            a.put("latitude", 12.9716);
            a.put("longitude", 77.5946);
        }
        String addressId = post("/v1/customer/addresses", a, token, JsonNode.class).getBody().get("addressId").asText();
        ResponseEntity<JsonNode> q = quote(token, addressId, 1);
        assertThat(q.getStatusCode().value()).as("quote: %s", q.getBody()).isEqualTo(200);
        String quoteId = q.getBody().get("quoteId").asText();
        String customerId = db.getCollection("checkout_quotes").find(new Document("_id", quoteId)).first()
                .getString("customerId");
        return new Shopper(token, customerId, sku, addressId, quoteId, pin);
    }

    private ResponseEntity<JsonNode> place(String token, Object body) {
        return call(HttpMethod.POST, "/v1/customer/orders", token, null, body);
    }

    private ResponseEntity<JsonNode> placeCod(Shopper s) {
        return place(s.token(), Map.of("quoteId", s.quoteId(), "paymentMethod", "COD"));
    }

    private ResponseEntity<JsonNode> read(String token, String orderId) {
        return call(HttpMethod.GET, "/v1/customer/orders/" + orderId, token, null, null);
    }

    private void setPrice(String sku, long sellingPaise) {
        db.getCollection("price_current").updateOne(new Document("sku_id", sku),
                new Document("$set", new Document("selling_price_paise", sellingPaise)));
    }

    private void setStock(String sku, long onHand) {
        db.getCollection("inventory").updateOne(new Document("sku_id", sku).append("fulfillment_location_id", LOC),
                new Document("$set", new Document("on_hand", onHand)));
    }

    private long onHand(String sku) {
        return db.getCollection("inventory").find(new Document("sku_id", sku).append("fulfillment_location_id", LOC))
                .first().get("on_hand", Number.class).longValue();
    }

    private Document cart(Shopper s) {
        return db.getCollection("customer_carts").find(new Document("_id", s.customerId())).first();
    }

    private Document orderDoc(String orderId) {
        return db.getCollection("orders").find(new Document("_id", orderId)).first();
    }

    private double count(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private static Set<String> keys(JsonNode n) {
        Set<String> out = new TreeSet<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static void assertSafeError(ResponseEntity<JsonNode> r, int status, String code) {
        assertThat(r.getStatusCode().value()).as("body %s", r.getBody()).isEqualTo(status);
        assertThat(r.getBody().get("code").asText()).isEqualTo(code);
        assertThat(r.getBody().get("message").asText()).isNotBlank();
        assertThat(r.getBody().get("requestId").asText()).isNotBlank();              // requestId on errors
        assertThat(keys(r.getBody())).containsExactlyInAnyOrder("code", "message", "requestId");
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
    }

    // ============================================================
    // security
    // ============================================================

    @Test void unauthenticated_post_and_get_are_rejected() {
        assertThat(place(null, Map.of("quoteId", "CHKQ_x", "paymentMethod", "COD")).getStatusCode().value()).isEqualTo(401);
        assertThat(read(null, "ORD_abcdefgh").getStatusCode().value()).isEqualTo(401);
        assertThat(place("garbage.token.value", Map.of("quoteId", "CHKQ_x", "paymentMethod", "COD"))
                .getStatusCode().value()).isEqualTo(401);
        assertThat(read("garbage.token.value", "ORD_abcdefgh").getStatusCode().value()).isEqualTo(401);
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", "nobody"))).isZero();
    }

    @Test void the_principal_is_the_customer_and_a_body_customerId_cannot_override_it() {
        Shopper s = shopper();
        Map<String, Object> body = new HashMap<>();
        body.put("quoteId", s.quoteId());
        body.put("paymentMethod", "COD");
        body.put("customerId", "CUS_someoneelse00000");
        ResponseEntity<JsonNode> r = place(s.token(), body);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(orderDoc(r.getBody().get("orderId").asText()).getString("customerId")).isEqualTo(s.customerId());
    }

    @Test void a_foreign_quote_cannot_place_an_order_and_is_indistinguishable_from_an_unknown_quote() {
        Shopper owner = shopper();
        Shopper intruder = shopper();
        ResponseEntity<JsonNode> foreign = place(intruder.token(), Map.of("quoteId", owner.quoteId(), "paymentMethod", "COD"));
        ResponseEntity<JsonNode> unknown = place(intruder.token(), Map.of("quoteId", "CHKQ_doesnotexist123", "paymentMethod", "COD"));
        assertSafeError(foreign, 404, "NOT_FOUND");
        assertSafeError(unknown, 404, "NOT_FOUND");
        assertThat(foreign.getBody().get("message")).isEqualTo(unknown.getBody().get("message"));
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", owner.customerId()))).isZero();
        assertThat(onHand(owner.sku())).isEqualTo(10);
    }

    @Test void a_foreign_unknown_and_malformed_order_id_are_the_identical_404() {
        Shopper owner = shopper();
        Shopper other = shopper();
        String orderId = placeCod(owner).getBody().get("orderId").asText();
        ResponseEntity<JsonNode> foreign = read(other.token(), orderId);
        ResponseEntity<JsonNode> unknown = read(other.token(), "ORD_doesnotexist12345678");
        ResponseEntity<JsonNode> malformed = read(other.token(), "not-an-order-id");
        for (ResponseEntity<JsonNode> r : List.of(foreign, unknown, malformed)) assertSafeError(r, 404, "NOT_FOUND");
        assertThat(foreign.getBody().get("message")).isEqualTo(unknown.getBody().get("message"));
        assertThat(foreign.getBody().get("message")).isEqualTo(malformed.getBody().get("message"));
        assertThat(read(owner.token(), orderId).getStatusCode().value()).isEqualTo(200); // the owner still can
    }

    @Test void raw_json_never_leaks_internal_fields() {
        Shopper s = shopper();
        ResponseEntity<JsonNode> r = placeCod(s);
        Document stored = orderDoc(r.getBody().get("orderId").asText());
        String raw = r.getBody().toString();
        for (String secret : List.of(s.customerId(), s.quoteId(), s.addressId(), stored.getString("reservationId"), LOC)) {
            assertThat(raw).as("must not contain %s", secret).doesNotContain(secret);
        }
        for (String field : List.of("customerId", "quoteId", "reservationId", "addressId", "addressVersion",
                "fulfillmentLocationId", "latitude", "longitude", "version", "_id")) {
            assertThat(raw).as("must not expose field %s", field).doesNotContain("\"" + field + "\"");
        }
        assertThat(keys(r.getBody())).containsExactlyInAnyOrder("orderId", "status", "paymentMethod",
                "paymentCondition", "items", "itemCount", "subtotalPaise", "currency", "deliveryAddress",
                "createdAt", "confirmedAt", "money", "requestId");
        assertThat(keys(r.getBody().get("money"))).containsExactlyInAnyOrder("merchandiseSubtotalPaise",
                "benefitDiscountPaise", "payablePaise");
        assertThat(keys(r.getBody().get("items").get(0))).containsExactlyInAnyOrder("skuId", "title", "brandCode",
                "quantity", "unitPricePaise", "lineTotalPaise");
        assertThat(keys(r.getBody().get("deliveryAddress"))).containsExactlyInAnyOrder("label", "recipientName",
                "recipientPhone", "addressLine1", "addressLine2", "landmark", "city", "state", "postalCode");
        // error bodies are equally clean
        Shopper t = shopper();
        db.getCollection("price_current").updateOne(new Document("sku_id", t.sku()),
                new Document("$set", new Document("selling_price_paise", 9999L)));
        assertThat(placeCod(t).getBody().toString()).doesNotContain(t.customerId()).doesNotContain(LOC)
                .doesNotContain("reservation");
    }

    // ============================================================
    // POST: success, replay, request validation
    // ============================================================

    @Test void a_valid_cod_request_places_a_confirmed_order_and_commits_the_whole_invariant() {
        Shopper s = shopper();
        ResponseEntity<JsonNode> r = placeCod(s);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        JsonNode o = r.getBody();
        assertThat(o.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(o.get("paymentMethod").asText()).isEqualTo("COD");
        assertThat(o.get("paymentCondition").asText()).isEqualTo("COD_DUE");
        assertThat(o.get("orderId").asText()).matches("^ORD_[A-Za-z0-9_-]{6,64}$");
        assertThat(o.get("itemCount").asInt()).isEqualTo(2);
        assertThat(o.get("subtotalPaise").asLong()).isEqualTo(10000);
        assertThat(o.get("currency").asText()).isEqualTo("INR");
        assertThat(o.get("confirmedAt").asText()).isNotBlank();
        assertThat(o.get("requestId").asText()).isNotBlank();
        assertThat(o.get("items").get(0).get("skuId").asText()).isEqualTo(s.sku());
        assertThat(o.get("deliveryAddress").get("addressLine1").asText()).isEqualTo("12 MG Road");

        Document stored = orderDoc(o.get("orderId").asText());                        // committed state
        assertThat(stored.getString("status")).isEqualTo("CONFIRMED");
        assertThat(db.getCollection("inventory_reservations").find(new Document("_id", stored.getString("reservationId")))
                .first().getString("status")).isEqualTo("CONSUMED");
        assertThat(onHand(s.sku())).isEqualTo(8);
        Document cart = cart(s);
        assertThat(cart.getList("items", Document.class)).isEmpty();
        assertThat(cart.get("version", Number.class).longValue()).isEqualTo(2);
        assertThat(cart.get("purchasedThroughVersion", Number.class).longValue()).isEqualTo(1);
    }

    @Test void a_same_quote_replay_is_200_with_the_same_order_and_does_not_double_decrement_stock() {
        Shopper s = shopper();
        JsonNode first = placeCod(s).getBody();
        ResponseEntity<JsonNode> replay = placeCod(s);
        ResponseEntity<JsonNode> again = placeCod(s);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("orderId")).isEqualTo(first.get("orderId"));
        assertThat(again.getBody().get("orderId")).isEqualTo(first.get("orderId"));
        assertThat(replay.getBody().get("confirmedAt")).isEqualTo(first.get("confirmedAt"));
        assertThat(onHand(s.sku())).isEqualTo(8);                                     // decremented once
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", s.customerId()))).isEqualTo(1);
        assertThat(db.getCollection("inventory_reservations").countDocuments(
                new Document("orderId", first.get("orderId").asText()))).isEqualTo(1);
        assertThat(cart(s).get("version", Number.class).longValue()).isEqualTo(2);    // cleared once
    }

    @Test void unsupported_or_missing_payment_method_is_400() {
        Shopper s = shopper();
        for (Object pm : List.of("CARD", "cod", "Cod", "", "UPI", " COD")) {
            assertSafeError(place(s.token(), Map.of("quoteId", s.quoteId(), "paymentMethod", pm)), 400,
                    "PAYMENT_METHOD_UNSUPPORTED");
        }
        assertSafeError(place(s.token(), Map.of("quoteId", s.quoteId())), 400, "INVALID_REQUEST");
        assertSafeError(place(s.token(), Map.of("quoteId", s.quoteId(), "paymentMethod", 7)), 400, "INVALID_REQUEST");
        Map<String, Object> nullPm = new HashMap<>();
        nullPm.put("quoteId", s.quoteId());
        nullPm.put("paymentMethod", null);
        assertSafeError(place(s.token(), nullPm), 400, "INVALID_REQUEST");
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", s.customerId()))).isZero();
        assertThat(onHand(s.sku())).isEqualTo(10);
    }

    @Test void missing_or_non_string_quote_id_and_a_malformed_body_are_400() {
        Shopper s = shopper();
        assertSafeError(place(s.token(), Map.of("paymentMethod", "COD")), 400, "INVALID_REQUEST");
        assertSafeError(place(s.token(), Map.of("quoteId", 5, "paymentMethod", "COD")), 400, "INVALID_REQUEST");
        assertSafeError(place(s.token(), List.of("quoteId")), 400, "INVALID_REQUEST");
        assertSafeError(place(s.token(), null), 400, "INVALID_REQUEST");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(s.token());
        assertSafeError(rest.exchange(url("/v1/customer/orders"), HttpMethod.POST, new HttpEntity<>("{not json", h),
                JsonNode.class), 400, "INVALID_REQUEST");
    }

    @Test void a_non_json_content_type_is_415() {
        Shopper s = shopper();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_PLAIN);
        h.setBearerAuth(s.token());
        assertSafeError(rest.exchange(url("/v1/customer/orders"), HttpMethod.POST,
                new HttpEntity<>("quoteId=" + s.quoteId(), h), JsonNode.class), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test void a_malformed_quote_id_is_the_same_404_as_an_unknown_one() {
        Shopper s = shopper();
        for (String q : List.of("not-a-quote", "CHKQ_", "x".repeat(200), "CHKQ_../../etc")) {
            assertSafeError(place(s.token(), Map.of("quoteId", q, "paymentMethod", "COD")), 404, "NOT_FOUND");
        }
    }

    @Test void unknown_body_fields_are_ignored_and_grant_no_authority() {
        Shopper s = shopper();
        Map<String, Object> body = new HashMap<>();
        body.put("quoteId", s.quoteId());
        body.put("paymentMethod", "COD");
        body.put("status", "CREATED");
        body.put("paymentCondition", "PAID");
        body.put("reservationId", "RSV_attacker");
        body.put("fulfillmentLocationId", "FUL-ATTACKER");
        body.put("subtotalPaise", 1);
        body.put("customerId", "CUS_attacker0000000");
        ResponseEntity<JsonNode> r = place(s.token(), body);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(r.getBody().get("paymentCondition").asText()).isEqualTo("COD_DUE");
        assertThat(r.getBody().get("subtotalPaise").asLong()).isEqualTo(10000);
        Document stored = orderDoc(r.getBody().get("orderId").asText());
        assertThat(stored.getString("reservationId")).doesNotContain("attacker");
        assertThat(db.getCollection("inventory_reservations").find(new Document("_id", stored.getString("reservationId")))
                .first().getString("fulfillmentLocationId")).isEqualTo(LOC);
    }

    // ============================================================
    // POST: domain failures
    // ============================================================

    @Test void an_expired_quote_is_410_and_writes_nothing() {
        Shopper s = shopper();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", s.quoteId()), new Document("$set",
                new Document("createdAt", Date.from(Instant.now().minusSeconds(600)))
                        .append("expiresAt", Date.from(Instant.now().minusSeconds(60)))));
        assertSafeError(placeCod(s), 410, "QUOTE_EXPIRED");
        assertNothingPlaced(s);
    }

    @Test void a_changed_price_is_409() {
        Shopper s = shopper();
        setPrice(s.sku(), 5500);
        assertSafeError(placeCod(s), 409, "PRICE_CHANGED");
        assertNothingPlaced(s);
    }

    @Test void a_changed_address_is_409() {
        Shopper s = shopper();
        db.getCollection("customer_addresses").updateOne(new Document("_id", s.addressId()),
                new Document("$inc", new Document("version", 1L)));
        assertSafeError(placeCod(s), 409, "ADDRESS_CHANGED");
        assertNothingPlaced(s);
    }

    @Test void an_unserviceable_address_is_409() {
        Shopper s = shopper();
        db.getCollection("service_areas").deleteMany(new Document("pincode", s.pin()));
        assertSafeError(placeCod(s), 409, "NOT_SERVICEABLE");
        assertNothingPlaced(s);
    }

    @Test void an_unavailable_product_is_409() {
        Shopper s = shopper();
        db.getCollection("products").updateOne(new Document("_id", s.sku()),
                new Document("$set", new Document("lifecycle", "archived")));
        assertSafeError(placeCod(s), 409, "PRODUCT_UNAVAILABLE");
        assertNothingPlaced(s);
    }

    @Test void insufficient_stock_is_409() {
        Shopper s = shopper();
        setStock(s.sku(), 1);
        assertSafeError(placeCod(s), 409, "STOCK_UNAVAILABLE");
        assertNothingPlaced(s, 1);
    }

    @Test void a_reservation_that_expires_before_consume_is_409_and_writes_nothing() {
        Shopper s = shopper();
        MODE.set(Mode.RESERVATION_EXPIRED);
        ResponseEntity<JsonNode> r = placeCod(s);
        MODE.set(Mode.NORMAL);
        assertSafeError(r, 409, "RESERVATION_EXPIRED");
        assertThat(r.getBody().toString()).doesNotContain("secret").doesNotContain(LOC);
        assertNothingPlaced(s);
    }

    @Test void a_second_quote_from_an_already_ordered_cart_is_409() {
        Shopper s = shopper();
        ResponseEntity<JsonNode> second = quote(s.token(), s.addressId(), 1); // another quote, same cart v1
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        String secondQuote = second.getBody().get("quoteId").asText();
        assertThat(placeCod(s).getStatusCode().value()).isEqualTo(200);
        assertSafeError(place(s.token(), Map.of("quoteId", secondQuote, "paymentMethod", "COD")), 409,
                "CART_VERSION_ALREADY_PURCHASED");
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", s.customerId()))).isEqualTo(1);
        assertThat(onHand(s.sku())).isEqualTo(8);
    }

    private void assertNothingPlaced(Shopper s) {
        assertNothingPlaced(s, 10);
    }

    private void assertNothingPlaced(Shopper s, long expectedOnHand) {
        assertThat(db.getCollection("orders").countDocuments(new Document("customerId", s.customerId()))).isZero();
        assertThat(onHand(s.sku())).isEqualTo(expectedOnHand);
        Document cart = cart(s);
        assertThat(cart.getList("items", Document.class)).isNotEmpty(); // cart NOT cleared
        assertThat(cart.get("version", Number.class).longValue()).isEqualTo(1);
        assertThat(cart.containsKey("purchasedThroughVersion")).isFalse();
    }

    // ============================================================
    // POST/GET: outage, integrity, defects
    // ============================================================

    @Test void a_datastore_outage_is_a_safe_503_on_both_endpoints() {
        Shopper s = shopper();
        MODE.set(Mode.OUTAGE);
        ResponseEntity<JsonNode> post;
        ResponseEntity<JsonNode> get;
        try {
            post = placeCod(s);
            get = read(s.token(), "ORD_anything123456789");
        } finally {
            MODE.set(Mode.NORMAL);
        }
        assertSafeError(post, 503, "SERVICE_UNAVAILABLE");
        assertSafeError(get, 503, "SERVICE_UNAVAILABLE");
        assertThat(post.getBody().toString()).doesNotContain("secret").doesNotContain("10.0.0.1");
        assertThat(get.getBody().toString()).doesNotContain("secret").doesNotContain("10.0.0.1");
        assertNothingPlaced(s);
    }

    @Test void an_integrity_failure_is_a_safe_500() {
        Shopper s = shopper();
        // an internal CREATED order for this quote: COD placement must fail closed and never convert it
        orderService.createOrder(new CustomerId(s.customerId()), s.quoteId(), PaymentMethod.COD);
        ResponseEntity<JsonNode> r = placeCod(s);
        assertSafeError(r, 500, "INTERNAL");
        assertThat(r.getBody().toString()).doesNotContain("CREATED").doesNotContain("reservation")
                .doesNotContain("convert");
        assertThat(db.getCollection("orders").find(new Document("customerId", s.customerId())).first()
                .getString("status")).isEqualTo("CREATED");
    }

    @Test void an_unexpected_defect_is_a_safe_500_with_no_detail() {
        Shopper s = shopper();
        MODE.set(Mode.DEFECT);
        ResponseEntity<JsonNode> post;
        ResponseEntity<JsonNode> get;
        try {
            post = placeCod(s);
            get = read(s.token(), "ORD_anything123456789");
        } finally {
            MODE.set(Mode.NORMAL);
        }
        assertSafeError(post, 500, "INTERNAL");
        assertSafeError(get, 500, "INTERNAL");
        assertThat(post.getBody().toString()).doesNotContain("secret").doesNotContain("pii@example.com");
        assertThat(get.getBody().toString()).doesNotContain("secret").doesNotContain("pii@example.com");
    }

    // ============================================================
    // GET
    // ============================================================

    @Test void the_owner_reads_the_order_exactly_as_placed() {
        Shopper s = shopper();
        JsonNode placed = placeCod(s).getBody();
        ResponseEntity<JsonNode> r = read(s.token(), placed.get("orderId").asText());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        assertThat(r.getBody().get("confirmedAt").asText()).isEqualTo(placed.get("confirmedAt").asText());
        for (String field : List.of("orderId", "status", "paymentMethod", "paymentCondition", "items", "itemCount",
                "subtotalPaise", "currency", "deliveryAddress", "createdAt", "confirmedAt")) {
            assertThat(r.getBody().get(field)).as(field).isEqualTo(placed.get(field));
        }
    }

    @Test void the_response_is_the_stored_snapshot_not_current_commerce_state() {
        Shopper s = shopper();
        JsonNode placed = placeCod(s).getBody();
        String orderId = placed.get("orderId").asText();
        // mutate product title/brand, pricing and the address AFTER placement
        db.getCollection("products").updateOne(new Document("_id", s.sku()),
                new Document("$set", new Document("title", "RENAMED").append("brand_code", "OTHER")));
        setPrice(s.sku(), 7777);
        db.getCollection("customer_addresses").updateOne(new Document("_id", s.addressId()), new Document("$set",
                new Document("addressLine1", "MOVED STREET").append("city", "Mumbai").append("recipientName", "Someone Else")
                        .append("version", 9L)));

        JsonNode after = read(s.token(), orderId).getBody();

        assertThat(after.get("items")).isEqualTo(placed.get("items"));
        assertThat(after.get("deliveryAddress")).isEqualTo(placed.get("deliveryAddress"));
        assertThat(after.get("subtotalPaise")).isEqualTo(placed.get("subtotalPaise"));
        assertThat(after.get("items").get(0).get("title").asText()).doesNotContain("RENAMED");
        assertThat(after.get("items").get(0).get("brandCode").asText()).isEqualTo("BR");
        assertThat(after.get("items").get(0).get("unitPricePaise").asLong()).isEqualTo(5000);
        assertThat(after.get("deliveryAddress").get("addressLine1").asText()).isEqualTo("12 MG Road");
    }

    @Test void a_corrupt_stored_order_is_a_safe_500_and_never_defaulted() {
        Shopper s = shopper();
        String orderId = placeCod(s).getBody().get("orderId").asText();
        db.getCollection("orders").updateOne(new Document("_id", orderId),
                new Document("$unset", new Document("version", "")));
        assertSafeError(read(s.token(), orderId), 500, "INTERNAL");
    }

    @Test void an_internal_CREATED_order_is_not_customer_visible() {
        Shopper s = shopper();
        Order created = orderService.createOrder(new CustomerId(s.customerId()), s.quoteId(), PaymentMethod.COD);
        assertSafeError(read(s.token(), created.orderId().value()), 404, "NOT_FOUND");
    }

    // ============================================================
    // optional coordinates
    // ============================================================

    @Test void a_coordinate_less_address_places_and_reads_a_normal_order_with_no_coordinates_anywhere() {
        Shopper with = shopper(true);
        Shopper without = shopper(false);
        assertThat(db.getCollection("customer_addresses").find(new Document("_id", without.addressId())).first()
                .get("latitude")).isNull(); // the address really is coordinate-less (valid per the Address domain)

        ResponseEntity<JsonNode> r = placeCod(without);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(r.getBody().get("paymentMethod").asText()).isEqualTo("COD");
        assertThat(r.getBody().get("paymentCondition").asText()).isEqualTo("COD_DUE");
        String orderId = r.getBody().get("orderId").asText();

        Document stored = orderDoc(orderId);                                               // persisted as absent
        assertThat(stored.get("addressSnapshot", Document.class).get("latitude")).isNull();
        assertThat(stored.get("addressSnapshot", Document.class).get("longitude")).isNull();
        assertThat(db.getCollection("inventory_reservations").find(new Document("_id", stored.getString("reservationId")))
                .first().getString("status")).isEqualTo("CONSUMED");                        // inventory consumed
        assertThat(onHand(without.sku())).isEqualTo(8);
        assertThat(cart(without).getList("items", Document.class)).isEmpty();               // cart finalized
        assertThat(cart(without).get("purchasedThroughVersion", Number.class).longValue()).isEqualTo(1);

        ResponseEntity<JsonNode> g = read(without.token(), orderId);                        // GET works
        assertThat(g.getStatusCode().value()).isEqualTo(200);
        for (JsonNode body : List.of(r.getBody(), g.getBody())) {
            assertThat(body.toString()).doesNotContain("latitude").doesNotContain("longitude");
        }
        assertThat(g.getBody().get("deliveryAddress").get("addressLine1").asText()).isEqualTo("12 MG Road");

        // the public contract is identical with and without coordinates
        JsonNode withBody = placeCod(with).getBody();
        assertThat(keys(g.getBody())).isEqualTo(keys(withBody));
        assertThat(keys(g.getBody().get("deliveryAddress"))).isEqualTo(keys(withBody.get("deliveryAddress")));
        assertThat(withBody.toString()).doesNotContain("latitude").doesNotContain("longitude");

        assertThat(placeCod(without).getBody().get("orderId").asText()).isEqualTo(orderId); // replay unchanged
        assertThat(onHand(without.sku())).isEqualTo(8);
    }

    @Test void a_corrupt_half_coordinate_pair_in_a_stored_order_is_a_safe_500_in_both_directions() {
        Shopper s = shopper(true);
        String orderId = placeCod(s).getBody().get("orderId").asText();

        db.getCollection("orders").updateOne(new Document("_id", orderId),
                new Document("$unset", new Document("addressSnapshot.longitude", ""))); // latitude present only
        ResponseEntity<JsonNode> a = read(s.token(), orderId);
        assertSafeError(a, 500, "INTERNAL");

        db.getCollection("orders").updateOne(new Document("_id", orderId), new Document("$set",
                new Document("addressSnapshot.longitude", 77.5946)).append("$unset",
                new Document("addressSnapshot.latitude", ""))); // longitude present only
        ResponseEntity<JsonNode> b = read(s.token(), orderId);
        assertSafeError(b, 500, "INTERNAL");
        for (ResponseEntity<JsonNode> r : List.of(a, b)) {
            assertThat(r.getBody().toString()).doesNotContain("latitude").doesNotContain("longitude")
                    .doesNotContain("both present");
        }
    }

    // ============================================================
    // metrics: no double counting
    // ============================================================

    @Test void a_failed_post_is_counted_once_by_the_domain_and_not_again_by_http() {
        Shopper s = shopper();
        setPrice(s.sku(), 5500);
        double domainBefore = count("order_place_cod_failure", "reason", "price_changed");
        double httpBefore = count("customer_order_http_failure");
        assertSafeError(placeCod(s), 409, "PRICE_CHANGED");
        assertThat(count("order_place_cod_failure", "reason", "price_changed") - domainBefore).isEqualTo(1);
        assertThat(count("customer_order_http_failure") - httpBefore).isZero();
    }

    @Test void a_successful_post_is_counted_once_by_the_domain_and_a_get_by_the_read_counter() {
        Shopper s = shopper();
        double placeBefore = count("order_place_cod_success");
        double readBefore = count("customer_order_read_success");
        double httpBefore = count("customer_order_http_failure");
        String orderId = placeCod(s).getBody().get("orderId").asText();
        assertThat(count("order_place_cod_success") - placeBefore).isEqualTo(1);
        assertThat(count("customer_order_read_success") - readBefore).isZero();     // a POST is not a read
        assertThat(read(s.token(), orderId).getStatusCode().value()).isEqualTo(200);
        assertThat(count("customer_order_read_success") - readBefore).isEqualTo(1);
        assertThat(count("order_place_cod_success") - placeBefore).isEqualTo(1);    // a read is not a placement
        assertThat(count("customer_order_http_failure") - httpBefore).isZero();
    }

    @Test void request_level_rejections_and_get_failures_are_counted_at_the_http_layer_only() {
        Shopper s = shopper();
        double domainBefore = count("order_place_cod_failure");
        double invalid = count("customer_order_http_failure", "operation", "place", "reason", "invalid_request");
        double unsupported = count("customer_order_http_failure", "operation", "place", "reason",
                "payment_method_unsupported");
        double notFound = count("customer_order_http_failure", "operation", "read", "reason", "not_found");
        place(s.token(), Map.of("paymentMethod", "COD"));
        place(s.token(), Map.of("quoteId", s.quoteId(), "paymentMethod", "CARD"));
        read(s.token(), "ORD_doesnotexist12345678");
        assertThat(count("customer_order_http_failure", "operation", "place", "reason", "invalid_request") - invalid).isEqualTo(1);
        assertThat(count("customer_order_http_failure", "operation", "place", "reason",
                "payment_method_unsupported") - unsupported).isEqualTo(1);
        assertThat(count("customer_order_http_failure", "operation", "read", "reason", "not_found") - notFound).isEqualTo(1);
        assertThat(count("order_place_cod_failure") - domainBefore).as("the domain never saw them").isZero();
    }

    // ============================================================
    // reachability
    // ============================================================

    @Test void there_is_no_order_list_endpoint_and_only_the_two_routes_exist() {
        Shopper s = shopper();
        ResponseEntity<JsonNode> list = call(HttpMethod.GET, "/v1/customer/orders", s.token(), null, null);
        assertThat(list.getStatusCode().is2xxSuccessful()).isFalse();
        for (HttpMethod m : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            assertThat(call(m, "/v1/customer/orders/ORD_abcdefgh", s.token(), null, Map.of()).getStatusCode()
                    .is2xxSuccessful()).isFalse();
        }
        List<String> routes = new ArrayList<>();
        for (var m : OrderController.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                    || m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)) {
                routes.add(m.getName());
            }
        }
        assertThat(routes).containsExactlyInAnyOrder("place", "read");
    }

    // ============================================================
    // Public Order money: the AUTHORITATIVE money computed at placement; the quote's moneyPreview stays advisory
    // ============================================================

    private ResponseEntity<JsonNode> getQuote(Shopper s) {
        return call(HttpMethod.GET, "/v1/customer/checkout/quotes/" + s.quoteId(), s.token(), null, null);
    }

    @Test void the_order_exposes_its_authoritative_money_and_get_and_replay_return_it_identically() {
        Shopper s = shopper();

        ResponseEntity<JsonNode> placed = placeCod(s);
        assertThat(placed.getStatusCode().value()).isEqualTo(200);
        JsonNode money = placed.getBody().get("money");
        assertThat(money.toString()).as("2 x 5000, no benefit (zero production rules)")
                .isEqualTo("{\"merchandiseSubtotalPaise\":10000,\"benefitDiscountPaise\":0,\"payablePaise\":10000}");
        assertThat(money.get("merchandiseSubtotalPaise").asLong()).isEqualTo(placed.getBody().get("subtotalPaise").asLong());
        assertThat(placed.getBody().get("paymentCondition").asText()).isEqualTo("COD_DUE");

        String orderId = placed.getBody().get("orderId").asText();
        assertThat(read(s.token(), orderId).getBody().get("money")).as("GET returns the persisted money").isEqualTo(money);
        assertThat(placeCod(s).getBody().get("money")).as("a replay returns the same money").isEqualTo(money);
        Document stored = (Document) orderDoc(orderId).get("money");
        assertThat(stored.get("payablePaise", Number.class).longValue()).isEqualTo(money.get("payablePaise").asLong());
    }

    @Test void a_quote_whose_advisory_money_differs_still_places_and_the_order_shows_its_own_authoritative_money() {
        Shopper s = shopper();
        // the quote shows a 1000 discount (advisory payable 9000) ...
        db.getCollection("checkout_quotes").updateOne(new Document("_id", s.quoteId()), new Document("$set", new Document()
                .append("benefits", new Document("outcome", "APPLIED").append("eligibleSubtotalPaise", 10_000L)
                        .append("discountPaise", 1_000L).append("discountBps", 1_000))
                .append("money", new Document("merchandiseSubtotalPaise", 10_000L).append("benefitDiscountPaise", 1_000L)
                        .append("payablePaise", 9_000L))));
        assertThat(getQuote(s).getBody().get("moneyPreview").get("payablePaise").asLong()).isEqualTo(9_000);

        ResponseEntity<JsonNode> r = placeCod(s);       // ... but this customer has no benefit now: the Order computes 10000

        assertThat(r.getStatusCode().value()).as("a money difference is not an error").isEqualTo(200);
        assertThat(r.getBody().get("money").toString())
                .isEqualTo("{\"merchandiseSubtotalPaise\":10000,\"benefitDiscountPaise\":0,\"payablePaise\":10000}");
        assertThat(getQuote(s).getBody().get("moneyPreview").get("payablePaise").asLong())
                .as("the stored quote is untouched by the Order").isEqualTo(9_000);
    }

    @Test void a_legacy_quote_without_money_or_benefits_still_places_with_the_authoritative_money() {
        Shopper s = shopper();
        db.getCollection("checkout_quotes").updateOne(new Document("_id", s.quoteId()),
                new Document("$unset", new Document("money", "").append("benefits", "")));

        ResponseEntity<JsonNode> r = placeCod(s);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("money").get("payablePaise").asLong()).isEqualTo(10_000);
    }

    @Test void a_legacy_order_without_money_omits_the_field_it_is_never_a_zero_payable() {
        Shopper s = shopper();
        String orderId = placeCod(s).getBody().get("orderId").asText();
        // a historical order created before the money model: no persisted money (and no benefit snapshot, which money requires)
        db.getCollection("orders").updateOne(new Document("_id", orderId),
                new Document("$unset", new Document("money", "").append("benefits", "")));

        JsonNode body = read(s.token(), orderId).getBody();

        assertThat(body.has("money")).as("absent, never 0").isFalse();
        assertThat(body.get("subtotalPaise").asLong()).isEqualTo(10_000);
    }
}
