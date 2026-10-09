package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoClient;
import com.tazzzo.auth.otp.OtpDeliveryProvider;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.common.audit.DomainAudit;
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
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W: the whole customer journey over real HTTP, as the app performs it, with no shortcut through the database for
 * anything the customer does: browse a product -> check the PIN -> OTP request -> OTP verify -> session -> profile ->
 * save an address -> add to cart -> checkout quote -> place a COD order -> read it back -> refresh the session ->
 * log out -> the old access token is refused. Catalogue/stock/serviceability are seeded through their domain services
 * (the operator side). The only test double is the OTP delivery provider, which captures the code instead of sending SMS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CustomerJourneyE2EIT.TestBeans.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class CustomerJourneyE2EIT extends AbstractConsumerIT {

    static final String SKU = "TZP-90000001";
    static final String PIN = "560034";
    static final String LOC = "FL-E2E";
    static final String PHONE = "+919812300077";

    static String key(String seed) {
        return Base64.getEncoder().encodeToString(seed.getBytes(StandardCharsets.UTF_8));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_e2e_journey");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> key("e2e-cursor-fixture-key-32-bytes!"));
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> key("e2e-access-fixture-key-32-bytes!"));
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> key("e2e-refresh-fixture-key-32bytes!"));
        r.add("tazzzo.customer-auth.otp.hmac-key-b64", () -> key("e2e-otp-fixture-key-32-bytes-ok!"));
        for (String b : new String[]{"request-ip", "request-phone", "verify-ip", "verify-challenge"}) {
            r.add("tazzzo.customer-auth.otp." + b + ".capacity", () -> "1000");
            r.add("tazzzo.customer-auth.otp." + b + ".refill-per-second", () -> "1000");
        }
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    /** The one double: OTP "delivery" captures the code instead of sending an SMS. */
    @TestConfiguration
    static class TestBeans {
        static final Map<String, String> SENT = new ConcurrentHashMap<>();

        @Bean
        @Primary
        OtpDeliveryProvider capturingOtpDelivery() {
            return (Phone phone, String otp, Duration expiresIn) -> SENT.put(phone.value(), otp);
        }
    }

    @Autowired MongoClient client;

    @BeforeAll
    void operatorSeedsTheStore() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        Clock clock = Clock.systemUTC();
        Tx tx = new Tx(client);
        PricingService pricing = new PricingService(tx, new WritePath(db), clock);
        eligibleProduct(SKU, "TZV-000001");
        pricing.upsertPrice(new UpsertPriceCommand(SKU, 24900, 29900, Currency.INR, null, null, "seed", null));
        new ProductCardProjectionService(new CatalogCardReader(db), pricing, new MediaService(tx, new WritePath(db), clock), db,
                clock).rebuildOne(SKU);
        new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock).upsertServiceArea(new UpsertServiceAreaCommand(
                PIN, "SA-E2E", List.of(new ServiceabilityRoute(LOC, 0, true)), "seed", null));
        new InventoryService(tx, new WritePath(db), clock).setInventory(new SetInventoryCommand(SKU, LOC, 20, 2, 10, "seed", null));
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, String bearer, Map<String, String> headers, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        if (headers != null) headers.forEach(h::set);
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), JsonNode.class);
    }

    static JsonNode ok(ResponseEntity<JsonNode> r, int status, String step) {
        assertThat(r.getStatusCode().value()).as(step + " -> " + r.getBody()).isEqualTo(status);
        return r.getBody();
    }

    long onHand() {
        return db.getCollection("inventory").find(new Document("sku_id", SKU).append("fulfillment_location_id", LOC)).first()
                .get("on_hand", Number.class).longValue();
    }

    @Test
    void a_customer_browses_signs_in_checks_out_cash_on_delivery_and_signs_out() {
        // 1. browse (anonymous, public surface)
        JsonNode product = ok(call(HttpMethod.GET, "/v1/products/" + SKU, null, null, null), 200, "product detail");
        assertThat(product.toString()).contains(SKU).contains("24900");
        JsonNode area = ok(call(HttpMethod.GET, "/v1/serviceability?pin=" + PIN, null, null, null), 200, "serviceability");
        assertThat(area.toString()).contains("true");

        // 2. sign in with OTP
        JsonNode challenge = ok(call(HttpMethod.POST, "/v1/auth/otp/request", null, null, Map.of("phone", PHONE)), 202, "otp request");
        String otp = TestBeans.SENT.get(PHONE);
        assertThat(otp).as("the provider was handed a code").matches("\\d{4,8}");
        assertThat(challenge.toString()).doesNotContain(otp);
        JsonNode verified = ok(call(HttpMethod.POST, "/v1/auth/otp/verify", null, null,
                Map.of("challengeId", challenge.get("challengeId").asText(), "otp", otp)), 200, "otp verify");
        JsonNode session = ok(call(HttpMethod.POST, "/v1/auth/session", null, null,
                Map.of("grantId", verified.get("grantId").asText())), 200, "session");
        String access = session.get("accessToken").asText();
        String refresh = session.get("refreshToken").asText();
        assertThat(session.toString()).doesNotContain(PHONE);

        // 3. profile and address
        ok(call(HttpMethod.GET, "/v1/customer/profile", access, null, null), 200, "profile");
        Map<String, Object> address = new HashMap<>();
        address.put("label", "HOME");
        address.put("recipientName", "Asha Rao");
        address.put("recipientPhone", PHONE);
        address.put("addressLine1", "4 Residency Road");
        address.put("city", "Bengaluru");
        address.put("state", "Karnataka");
        address.put("postalCode", PIN);
        address.put("latitude", 12.9716);
        address.put("longitude", 77.5946);
        String addressId = ok(call(HttpMethod.POST, "/v1/customer/addresses", access, null, address), 201, "address")
                .get("addressId").asText();

        // 4. cart -> quote -> COD order
        ok(call(HttpMethod.PUT, "/v1/customer/cart/items/" + SKU, access, Map.of("If-Match", "\"cart-0\""),
                Map.of("quantity", 2)), 200, "add to cart");
        JsonNode quote = ok(call(HttpMethod.POST, "/v1/customer/checkout/quote", access,
                Map.of("If-Match", "\"cart-1\"", "Idempotency-Key", "idem-" + UUID.randomUUID()),
                Map.of("addressId", addressId)), 200, "checkout quote");
        JsonNode order = ok(call(HttpMethod.POST, "/v1/customer/orders", access, null,
                Map.of("quoteId", quote.get("quoteId").asText(), "paymentMethod", "COD")), 200, "place COD order");
        assertThat(order.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(order.get("paymentMethod").asText()).isEqualTo("COD");
        assertThat(order.toString()).contains("49800");
        assertThat(onHand()).as("stock moved exactly once").isEqualTo(18);
        String orderId = order.get("orderId").asText();

        // 5. the order is readable; the cart is empty
        assertThat(ok(call(HttpMethod.GET, "/v1/customer/orders/" + orderId, access, null, null), 200, "read order")
                .get("orderId").asText()).isEqualTo(orderId);
        JsonNode cart = ok(call(HttpMethod.GET, "/v1/customer/cart", access, null, null), 200, "cart after order");
        assertThat(cart.toString()).doesNotContain(SKU);

        // 6. refresh rotates the session; logout ends it
        JsonNode rotated = ok(call(HttpMethod.POST, "/v1/auth/refresh", null, null, Map.of("refreshToken", refresh)), 200, "refresh");
        String access2 = rotated.get("accessToken").asText();
        assertThat(rotated.get("refreshToken").asText()).isNotEqualTo(refresh);
        assertThat(call(HttpMethod.POST, "/v1/auth/refresh", null, null, Map.of("refreshToken", refresh)).getStatusCode().value())
                .as("a spent refresh token is refused").isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/v1/auth/logout", access2, null, null).getStatusCode().value()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, "/v1/customer/orders/" + orderId, access2, null, null).getStatusCode().value())
                .as("the session is gone").isEqualTo(401);
    }
}
