package com.tazzzo.customer.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.TestActors;
import com.tazzzo.common.money.Currency;
import com.tazzzo.customer.address.AddressLimitProperties;
import com.tazzzo.delivery.DeliverySlotService;
import com.tazzzo.delivery.SlotWindow;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real customer auth, cart, quote, COD placement and delivery slots: the shared fixture of the slot placement ITs. */
abstract class AbstractOrderSlotIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));
    static final String VERTICAL = "TZV-000001";
    static final String LOC = "FUL-SLOT-INTERNAL";
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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
    static class BigAddressLimit {
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
    @Autowired DeliverySlotService slots;
    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService changes;

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
    }

    private int seq = 0;

    record Shopper(String token, String customerId, String sku, String addressId, String quoteId, String pin, String areaId) { }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, String token, Map<String, String> extra, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        if (extra != null) extra.forEach(h::set);
        return rest.exchange(url(path), method, new HttpEntity<>(body, h), JsonNode.class);
    }

    private String token() {
        String phone = "+9197" + String.format("%08d", (System.nanoTime() / 7) % 100_000_000);
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(), Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody().get("accessToken").asText();
    }

    /** A customer with a 2-unit cart, an address in a fresh serviceable PIN/area, and a fresh quote. */
    Shopper shopper() {
        String pin = String.format("5604%02d", ++seq % 100);
        String area = "SA-SLOT-" + seq;
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand(pin, area, List.of(new ServiceabilityRoute(LOC, 0, true)), "seed", null));
        String id = ("TZP-9" + String.format("%05d", seq) + (System.nanoTime() % 1000));
        String sku = id.substring(0, Math.min(id.length(), 12));
        db.getCollection("products").insertOne(new Document("_id", sku).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", sku))
                .append("brand_code", "BR").append("title", "T " + sku).append("lifecycle", "active")
                .append("classification", new Document("vertical_id", VERTICAL).append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document()).append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date()));
        pricing.upsertPrice(new UpsertPriceCommand(sku, 5000, 7000, Currency.INR, null, null, "seed", null));
        inventory.setInventory(new SetInventoryCommand(sku, LOC, 10, 0, 10, "seed", null));
        String token = token();
        assertThat(call(HttpMethod.PUT, "/v1/customer/cart/items/" + sku, token, Map.of("If-Match", "\"cart-0\""), Map.of("quantity", 2)).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> a = new HashMap<>();
        a.put("label", "HOME");
        a.put("recipientName", "Ravi Kumar");
        a.put("recipientPhone", "+919876500001");
        a.put("addressLine1", "12 MG Road");
        a.put("city", "Bengaluru");
        a.put("state", "Karnataka");
        a.put("postalCode", pin);
        String addressId = post("/v1/customer/addresses", a, token, JsonNode.class).getBody().get("addressId").asText();
        ResponseEntity<JsonNode> q = call(HttpMethod.POST, "/v1/customer/checkout/quote", token,
                Map.of("If-Match", "\"cart-1\"", "Idempotency-Key", "idem-" + java.util.UUID.randomUUID()), Map.of("addressId", addressId));
        assertThat(q.getStatusCode().value()).as("quote: %s", q.getBody()).isEqualTo(200);
        String quoteId = q.getBody().get("quoteId").asText();
        String customerId = db.getCollection("checkout_quotes").find(new Document("_id", quoteId)).first().getString("customerId");
        return new Shopper(token, customerId, sku, addressId, quoteId, pin, area);
    }

    /** A window that is bookable all day tomorrow (and every later day in the horizon): 00:00-01:00 local, no cutoff. */
    void openWindow(String area, String windowId, int capacity) {
        slots.upsertWindow(TestActors.TEST, area, new SlotWindow(windowId, "Early " + windowId, 0, 60, 0, capacity, Set.of(1, 2, 3, 4, 5, 6, 7)), null);
    }

    static LocalDate tomorrow() {
        return Instant.now().atZone(IST).toLocalDate().plusDays(1);
    }

    ResponseEntity<JsonNode> place(String token, Object body) {
        return call(HttpMethod.POST, "/v1/customer/orders", token, null, body);
    }

    Map<String, Object> body(Shopper s, String slotId) {
        Map<String, Object> b = new HashMap<>();
        b.put("quoteId", s.quoteId());
        b.put("paymentMethod", "COD");
        if (slotId != null) b.put("deliverySlotId", slotId);
        return b;
    }

    long onHand(String sku) {
        return db.getCollection("inventory").find(new Document("sku_id", sku).append("fulfillment_location_id", LOC)).first().get("on_hand", Number.class).longValue();
    }
}
