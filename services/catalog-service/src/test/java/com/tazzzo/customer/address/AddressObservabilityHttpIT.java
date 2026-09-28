package com.tazzzo.customer.address;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12B — proves the minimum bounded address metrics exist, increment on the right outcomes, and
 * NEVER carry a high-cardinality/PII tag value.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AddressObservabilityHttpIT.TestBeans.class})
class AddressObservabilityHttpIT extends AbstractApiIT {

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
        AddressLimitProperties smallLimit() {
            AddressLimitProperties p = new AddressLimitProperties();
            p.setMaxActiveAddresses(1);
            return p;
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired MeterRegistry registry;

    private String newAccessToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        return established.get("accessToken").asText();
    }

    private Map<String, Object> validBody() {
        return Map.of("label", "HOME", "recipientName", "Sensitive Name", "recipientPhone", "+919876500001",
                "addressLine1", "Line1", "city", "City", "state", "State", "postalCode", "560047");
    }

    private ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(String path, String token, String ifMatch, Object body) {
        HttpHeaders h = headers(token);
        if (ifMatch != null) h.set("If-Match", ifMatch);
        return rest.exchange(url(path), HttpMethod.PATCH, new HttpEntity<>(body, h), JsonNode.class);
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test void create_success_and_read_success_increment() {
        String token = newAccessToken("+919876555001");
        double createBefore = counter("customer_address_create_success");
        double listBefore = counter("customer_address_list_success");
        post("/v1/customer/addresses", validBody(), token);
        get("/v1/customer/addresses", token, JsonNode.class);
        assertThat(counter("customer_address_create_success")).isEqualTo(createBefore + 1);
        assertThat(counter("customer_address_list_success")).isEqualTo(listBefore + 1);
    }

    @Test void failure_increments_with_bounded_operation_and_reason() {
        String token = newAccessToken("+919876555002");
        post("/v1/customer/addresses", validBody(), token); // now at limit (1)
        double before = counter("customer_address_failure", "operation", "create", "reason", "address_limit_reached");
        post("/v1/customer/addresses", validBody(), token);
        assertThat(counter("customer_address_failure", "operation", "create", "reason", "address_limit_reached"))
                .isEqualTo(before + 1);
    }

    @Test void serviceability_result_metric_increments() {
        String token = newAccessToken("+919876555003");
        double before = counter("address_serviceability_result", "result", "unserviceable");
        post("/v1/customer/addresses", validBody(), token);
        assertThat(counter("address_serviceability_result", "result", "unserviceable")).isEqualTo(before + 1);
    }

    // ---------- every AddressFailure is counted EXACTLY ONCE, wherever it originates ----------

    private double totalFailures() {
        return registry.find("customer_address_failure").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private ResponseEntity<JsonNode> rawJson(HttpMethod method, String path, String token, String rawBody,
                                             String ifMatch) {
        HttpHeaders h = headers(token);
        if (ifMatch != null) h.set("If-Match", ifMatch);
        return rest.exchange(url(path), method, new HttpEntity<>(rawBody, h), JsonNode.class);
    }

    private void assertCountedOnce(String operation, String reason, Runnable request) {
        double totalBefore = totalFailures();
        double taggedBefore = counter("customer_address_failure", "operation", operation, "reason", reason);
        request.run();
        assertThat(counter("customer_address_failure", "operation", operation, "reason", reason))
                .as("%s/%s incremented", operation, reason).isEqualTo(taggedBefore + 1);
        assertThat(totalFailures()).as("no other failure counter moved -- counted exactly once")
                .isEqualTo(totalBefore + 1);
    }

    @Test void invalid_create_field_is_counted_once() {
        String token = newAccessToken("+919876555010");
        Map<String, Object> body = new java.util.HashMap<>(validBody());
        body.put("postalCode", "12345");
        assertCountedOnce("create", "invalid_request", () -> post("/v1/customer/addresses", body, token));
    }

    @Test void missing_patch_if_match_is_counted_once() {
        String token = newAccessToken("+919876555011");
        String id = post("/v1/customer/addresses", validBody(), token).getBody().get("addressId").asText();
        assertCountedOnce("update", "precondition_required",
                () -> patch("/v1/customer/addresses/" + id, token, null, Map.of("recipientName", "X")));
    }

    @Test void malformed_patch_if_match_is_counted_once() {
        String token = newAccessToken("+919876555012");
        String id = post("/v1/customer/addresses", validBody(), token).getBody().get("addressId").asText();
        assertCountedOnce("update", "invalid_request",
                () -> patch("/v1/customer/addresses/" + id, token, "garbage", Map.of("recipientName", "X")));
    }

    @Test void invalid_patch_field_is_counted_once() {
        String token = newAccessToken("+919876555013");
        String id = post("/v1/customer/addresses", validBody(), token).getBody().get("addressId").asText();
        assertCountedOnce("update", "invalid_request",
                () -> patch("/v1/customer/addresses/" + id, token, "\"address-1\"", Map.of("postalCode", "1")));
    }

    @Test void malformed_delete_if_match_is_counted_once() {
        String token = newAccessToken("+919876555014");
        String id = post("/v1/customer/addresses", validBody(), token).getBody().get("addressId").asText();
        assertCountedOnce("delete", "invalid_request",
                () -> rawJson(HttpMethod.DELETE, "/v1/customer/addresses/" + id, token, null, "garbage"));
    }

    @Test void missing_delete_if_match_is_counted_once() {
        String token = newAccessToken("+919876555015");
        String id = post("/v1/customer/addresses", validBody(), token).getBody().get("addressId").asText();
        assertCountedOnce("delete", "precondition_required",
                () -> rawJson(HttpMethod.DELETE, "/v1/customer/addresses/" + id, token, null, null));
    }

    @Test void malformed_address_id_on_read_is_counted_once() {
        String token = newAccessToken("+919876555016");
        assertCountedOnce("read", "not_found",
                () -> rawJson(HttpMethod.GET, "/v1/customer/addresses/not-an-address-id", token, null, null));
    }

    @Test void malformed_json_on_create_is_counted_once() {
        String token = newAccessToken("+919876555017");
        assertCountedOnce("create", "invalid_request",
                () -> rawJson(HttpMethod.POST, "/v1/customer/addresses", token, "{not valid json", null));
    }

    @Test void malformed_json_on_patch_is_counted_once() {
        String token = newAccessToken("+919876555018");
        String id = post("/v1/customer/addresses", validBody(), token).getBody().get("addressId").asText();
        assertCountedOnce("update", "invalid_request",
                () -> rawJson(HttpMethod.PATCH, "/v1/customer/addresses/" + id, token, "{not valid json",
                        "\"address-1\""));
    }

    @Test void nan_latitude_literal_is_rejected_and_counted_once() {
        String token = newAccessToken("+919876555019");
        String body = "{\"label\":\"HOME\",\"recipientName\":\"N\",\"recipientPhone\":\"+919876500001\","
                + "\"addressLine1\":\"L\",\"city\":\"C\",\"state\":\"S\",\"postalCode\":\"560047\","
                + "\"latitude\":NaN,\"longitude\":77.6}";
        assertCountedOnce("create", "invalid_request",
                () -> rawJson(HttpMethod.POST, "/v1/customer/addresses", token, body, null));
    }

    // ---------- cardinality / privacy guard ----------

    private static final Pattern PHONE_LIKE = Pattern.compile("\\+91[6-9][0-9]{9}");
    private static final Pattern CUSTOMER_ID = Pattern.compile("\\bCUS_");
    private static final Pattern ADDRESS_ID = Pattern.compile("\\bADDR_");
    private static final Pattern PIN_LIKE = Pattern.compile("\\b560047\\b");
    private static final Pattern REQUEST_ID = Pattern.compile("\\breq_[0-9a-f]{8,}");
    private static final Set<String> ADDRESS_METER_NAMES = Set.of(
            "customer_address_list_success", "customer_address_read_success", "customer_address_create_success",
            "customer_address_update_success", "customer_address_delete_success",
            "customer_address_default_set_success", "customer_address_failure", "address_serviceability_result");
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("operation", "reason", "result");

    @Test void every_address_meter_uses_only_the_bounded_vocabulary_and_no_pii_tag() {
        String token = newAccessToken("+919876555099");
        ResponseEntity<JsonNode> created = post("/v1/customer/addresses", validBody(), token);
        String addressId = created.getBody().get("addressId").asText();
        patch("/v1/customer/addresses/" + addressId, token, "\"address-1\"", Map.of("recipientName", "Other"));
        post("/v1/customer/addresses", validBody(), token); // limit reached -> failure
        patch("/v1/customer/addresses/" + addressId, token, "\"address-99\"", Map.of("recipientName", "X")); // stale

        List<String> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!ADDRESS_METER_NAMES.contains(name)) continue;
            for (Tag tag : meter.getId().getTags()) {
                String k = tag.getKey();
                String v = tag.getValue();
                if (!ALLOWED_TAG_KEYS.contains(k)) violations.add(name + " has unexpected tag key " + k);
                if (PHONE_LIKE.matcher(v).find()) violations.add(name + " tag " + k + " looks like a phone: " + v);
                if (CUSTOMER_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a customerId");
                if (ADDRESS_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like an addressId");
                if (PIN_LIKE.matcher(v).find()) violations.add(name + " tag " + k + " looks like a PIN");
                if (REQUEST_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a requestId");
                if (v.contains("Sensitive Name")) violations.add(name + " tag " + k + " embeds recipientName");
                assertThat(v).as(name + " tag " + k + " must be lower_snake_case").matches("^[a-z_]+$");
            }
        }
        assertThat(violations).isEmpty();
    }
}
