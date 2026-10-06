package com.tazzzo.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin API (auth, validation, CAS, attributed audit) and the customer availability endpoint over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class DeliverySlotHttpIT extends AbstractApiIT {

    static final String CMS_TOKEN = "cms-test-token";     // the values AbstractApiIT configures (its constants are package-private)
    static final String READ_TOKEN = "read-test-token";
    static final String ADMIN = "/api/v1/admin/delivery-slots/SA-1";
    static final String CUSTOMER = "/v1/customer/delivery/slots";
    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @DynamicPropertySource
    static void sessionProps(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> ACCESS_KEY);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> REFRESH_KEY);
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired ServiceabilityService serviceability;
    @Autowired DeliverySlotService slots;

    @BeforeEach
    void setUp() {
        schemaBootstrap.bootstrap(db);
        for (String c : List.of("service_areas", "delivery_slot_windows", "delivery_slot_usage", "domain_events")) {
            db.getCollection(c).deleteMany(new Document());
        }
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand("560001", "SA-1",
                List.of(new ServiceabilityRoute("FL-1", 0, true)), "seed", null));
    }

    private String customerToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(), Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody().get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> put(String path, Object body, String token) {
        return rest.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private static Map<String, Object> win(Long expected, int capacity) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("label", "Early");
        m.put("startMinute", 0);
        m.put("endMinute", 60);
        m.put("cutoffMinutes", 0);
        m.put("capacity", capacity);
        m.put("days", List.of(1, 2, 3, 4, 5, 6, 7));
        if (expected != null) m.put("expectedVersion", expected);
        return m;
    }

    // ---------------------------------------------------------------- admin

    @Test
    void admin_create_read_update_list_and_lifecycle() {
        ResponseEntity<JsonNode> created = put(ADMIN + "/early", win(null, 3), CMS_TOKEN);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("version").asLong()).isEqualTo(1);
        assertThat(created.getBody().get("active").asBoolean()).isTrue();

        assertThat(put(ADMIN + "/early", win(null, 3), CMS_TOKEN).getStatusCode().value()).as("create is not an update").isEqualTo(409);
        ResponseEntity<JsonNode> updated = put(ADMIN + "/early", win(1L, 9), CMS_TOKEN);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().get("capacity").asInt()).isEqualTo(9);
        assertThat(put(ADMIN + "/early", win(1L, 9), CMS_TOKEN).getStatusCode().value()).as("stale").isEqualTo(409);

        assertThat(get(ADMIN, READ_TOKEN, JsonNode.class).getBody().get("items")).hasSize(1);
        ResponseEntity<JsonNode> off = post(ADMIN + "/early/deactivate", Map.of("expectedVersion", 2), CMS_TOKEN, JsonNode.class);
        assertThat(off.getBody().get("active").asBoolean()).isFalse();
        assertThat(post(ADMIN + "/early/activate", Map.of("expectedVersion", 3), CMS_TOKEN, JsonNode.class).getBody().get("active").asBoolean()).isTrue();
        assertThat(post(ADMIN + "/missing/activate", Map.of("expectedVersion", 1), CMS_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(404);
        assertThat(post(ADMIN + "/early/activate", Map.of(), CMS_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void admin_auth_validation_and_unknown_area() {
        assertThat(get(ADMIN, null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(put(ADMIN + "/early", win(null, 3), null).getStatusCode().value()).isEqualTo(401);
        assertThat(put(ADMIN + "/early", win(null, 3), READ_TOKEN).getStatusCode().value()).as("read-only credential cannot write").isEqualTo(403);
        assertThat(put(ADMIN + "/early", win(null, 0), CMS_TOKEN).getStatusCode().value()).isEqualTo(422);
        assertThat(put(ADMIN + "/Bad_ID", win(null, 3), CMS_TOKEN).getStatusCode().value()).isEqualTo(422);
        assertThat(put(ADMIN + "/early", Map.of("label", "x"), CMS_TOKEN).getStatusCode().value()).as("missing fields").isEqualTo(422);
        assertThat(put("/api/v1/admin/delivery-slots/SA-UNKNOWN/early", win(null, 3), CMS_TOKEN).getStatusCode().value()).isEqualTo(404);
        assertThat(get(ADMIN + "/early", READ_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(404);
        assertThat(db.getCollection("delivery_slot_windows").countDocuments()).isZero();
        assertThat(db.getCollection("domain_events").countDocuments(Filters.eq("aggregate_type", "delivery_slot_window"))).isZero();
    }

    @Test
    void admin_writes_carry_the_authenticated_actor_not_a_body_field() {
        Map<String, Object> body = new java.util.LinkedHashMap<>(win(null, 3));
        body.put("actor", Map.of("type", "HUMAN_ADMIN", "id", "google:evil"));
        put(ADMIN + "/early", body, CMS_TOKEN);
        Document e = db.getCollection("domain_events").find(Filters.eq("aggregate_type", "delivery_slot_window")).first();
        assertThat(e).isNotNull();
        assertThat(e.get("actor", Document.class).getString("type")).isEqualTo("SERVICE_ACCOUNT");
        assertThat(e.toJson()).doesNotContain("evil").doesNotContain(CMS_TOKEN);
    }

    // -------------------------------------------------------------- customer

    @Test
    void a_customer_sees_tomorrows_slots_with_status_only_and_no_capacity_or_internal_ids() {
        put(ADMIN + "/early", win(null, 1), CMS_TOKEN);
        String token = customerToken("+919876541001");
        ResponseEntity<JsonNode> res = get(CUSTOMER + "?pin=560001&days=3", token, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        JsonNode body = res.getBody();
        assertThat(body.get("serviceable").asBoolean()).isTrue();
        assertThat(body.get("timezone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(body.get("requestId").asText()).startsWith("req_");
        LocalDate tomorrow = Instant.now().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().plusDays(1);
        JsonNode slot = null;
        for (JsonNode s : body.get("slots")) {
            if (s.get("date").asText().equals(tomorrow.toString())) slot = s;
        }
        assertThat(slot).isNotNull();
        assertThat(slot.get("slotId").asText()).isEqualTo("early~" + tomorrow);
        assertThat(slot.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(slot.get("startsAt").asText()).endsWith("+05:30");
        String json = body.toString().toLowerCase();
        assertThat(json).doesNotContain("capacity").doesNotContain("remaining").doesNotContain("fl-1").doesNotContain("fulfillment")
                .doesNotContain("sa-1");

        new Tx(client).run(s -> slots.reserve(s, "SA-1", "early", tomorrow, "HOLD-1"));
        JsonNode after = get(CUSTOMER + "?pin=560001&days=3", token, JsonNode.class).getBody();
        for (JsonNode s : after.get("slots")) {
            if (s.get("date").asText().equals(tomorrow.toString())) assertThat(s.get("status").asText()).isEqualTo("FULL");
        }
    }

    @Test
    void an_unserviceable_pin_is_200_with_no_slots_and_bad_input_is_400() {
        put(ADMIN + "/early", win(null, 1), CMS_TOKEN);
        String token = customerToken("+919876541002");
        JsonNode none = get(CUSTOMER + "?pin=110001", token, JsonNode.class).getBody();
        assertThat(none.get("serviceable").asBoolean()).isFalse();
        assertThat(none.get("slots")).isEmpty();
        for (String q : new String[]{"", "?pin=abc", "?pin=56000", "?pin=560001&days=0", "?pin=560001&days=4", "?pin=560001&days=x",
                "?pin=560001&days=-1", "?pin=560001&days=01"}) {
            ResponseEntity<JsonNode> res = get(CUSTOMER + q, token, JsonNode.class);
            assertThat(res.getStatusCode().value()).as(q).isEqualTo(400);
            assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    void the_customer_endpoint_requires_a_customer_session() {
        assertThat(get(CUSTOMER + "?pin=560001", null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get(CUSTOMER + "?pin=560001", "garbage", JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get(CUSTOMER + "?pin=560001", CMS_TOKEN, JsonNode.class).getStatusCode().value()).as("a service token is not a customer").isEqualTo(401);
    }
}
