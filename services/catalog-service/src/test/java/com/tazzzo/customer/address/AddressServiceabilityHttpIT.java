package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12B §20/§24/§25/§38 — proves the address domain reuses the EXISTING serviceability domain
 * unchanged (never a parallel engine), evaluates it DYNAMICALLY on every response (never persisted
 * as address state), and preserves the three-state SERVICEABLE/UNSERVICEABLE/UNKNOWN result.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AddressServiceabilityHttpIT.TestBeans.class})
class AddressServiceabilityHttpIT extends AbstractApiIT {

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

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        AddressLimitProperties bigLimit() {
            AddressLimitProperties p = new AddressLimitProperties();
            p.setMaxActiveAddresses(10);
            return p;
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired ServiceabilityService serviceability;

    private String newAccessToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        return established.get("accessToken").asText();
    }

    private void seedServiceable(String pin) {
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand(pin, "SA-TEST-" + pin,
                List.of(new ServiceabilityRoute("FUL-INTERNAL-" + pin, 1, true)), "test", null));
    }

    private void seedUnserviceableButConfigured(String pin) {
        // an area exists but has NO active route -- still "not serviceable", distinct from an
        // absent PIN, but collapses to the same public serviceable=false either way.
        serviceability.upsertServiceArea(new UpsertServiceAreaCommand(pin, "SA-TEST-" + pin,
                List.of(new ServiceabilityRoute("FUL-INTERNAL-" + pin, 1, false)), "test", null));
    }

    private Map<String, Object> bodyWithPin(String pin) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", "HOME");
        m.put("recipientName", "Name");
        m.put("recipientPhone", "+919876500001");
        m.put("addressLine1", "Line1");
        m.put("city", "City");
        m.put("state", "State");
        m.put("postalCode", pin);
        return m;
    }

    @Test void saved_address_in_a_serviceable_pin_reports_serviceable_true() {
        seedServiceable("560101");
        String token = newAccessToken("+919876522001");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", bodyWithPin("560101"), token, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody().get("serviceability").get("serviceable").asBoolean()).isTrue();
    }

    @Test void saved_address_in_an_unconfigured_pin_reports_serviceable_false_but_still_saves() {
        String token = newAccessToken("+919876522002");
        // 560199 is never configured -- a valid PIN with no service area, the normal
        // outside-coverage answer. The mission's core rule: saving succeeds regardless (§24).
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", bodyWithPin("560199"), token, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody().get("serviceability").get("serviceable").asBoolean()).isFalse();
    }

    @Test void saved_address_in_a_configured_but_routeless_pin_reports_serviceable_false() {
        seedUnserviceableButConfigured("560102");
        String token = newAccessToken("+919876522003");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", bodyWithPin("560102"), token, JsonNode.class);
        assertThat(res.getBody().get("serviceability").get("serviceable").asBoolean()).isFalse();
    }

    @Test void serviceability_is_re_evaluated_on_every_read_without_rewriting_the_address_document() {
        String token = newAccessToken("+919876522004");
        // Not yet serviceable at creation time.
        String addressId = post("/v1/customer/addresses", bodyWithPin("560103"), token, JsonNode.class)
                .getBody().get("addressId").asText();
        assertThat(get("/v1/customer/addresses/" + addressId, token, JsonNode.class)
                .getBody().get("serviceability").get("serviceable").asBoolean()).isFalse();

        // Configuration changes AFTER the address was saved -- the address document itself is
        // never touched, but the NEXT read reflects the new configuration immediately.
        seedServiceable("560103");
        ResponseEntity<JsonNode> afterConfigChange = get("/v1/customer/addresses/" + addressId, token,
                JsonNode.class);
        assertThat(afterConfigChange.getBody().get("serviceability").get("serviceable").asBoolean()).isTrue();
    }

    @Test void response_never_exposes_fulfillment_location_or_service_area_id() {
        seedServiceable("560104");
        String token = newAccessToken("+919876522005");
        ResponseEntity<JsonNode> res = post("/v1/customer/addresses", bodyWithPin("560104"), token, JsonNode.class);
        String raw = res.getBody().toString();
        assertThat(raw).doesNotContain("FUL-INTERNAL-560104").doesNotContain("fulfillmentLocationId")
                .doesNotContain("SA-TEST-560104").doesNotContain("serviceAreaId");
    }

    @Test void default_address_status_is_independent_of_serviceability() {
        // isDefault reflects customer preference only, never "best serviceable address" (§26).
        String token = newAccessToken("+919876522006");
        String first = post("/v1/customer/addresses", bodyWithPin("560199"), token, JsonNode.class) // unserviceable
                .getBody().get("addressId").asText();
        seedServiceable("560105");
        post("/v1/customer/addresses", bodyWithPin("560105"), token, JsonNode.class); // serviceable, but NOT default

        ResponseEntity<JsonNode> firstNow = get("/v1/customer/addresses/" + first, token, JsonNode.class);
        assertThat(firstNow.getBody().get("isDefault").asBoolean())
                .as("first-saved address remains default even though it is unserviceable").isTrue();
    }
}
