package com.tazzzo.customer.profile;

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
 * PR-12A — proves the minimum bounded profile metrics exist, increment on the right outcomes, and
 * NEVER carry a high-cardinality/PII tag value. Mirrors {@code SessionObservabilityIT}'s
 * conventions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class CustomerProfileObservabilityIT extends AbstractApiIT {

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
    @Autowired MeterRegistry registry;

    private String newAccessToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        return established.get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> getProfile(String token) {
        return rest.exchange(url("/v1/customer/profile"), HttpMethod.GET, new HttpEntity<>(headers(token)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> patchProfile(String token, String ifMatch, Object body) {
        HttpHeaders h = headers(token);
        if (ifMatch != null) {
            h.set("If-Match", ifMatch);
        }
        return rest.exchange(url("/v1/customer/profile"), HttpMethod.PATCH, new HttpEntity<>(body, h),
                JsonNode.class);
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test void customer_profile_read_success_increments_on_a_real_get() {
        String token = newAccessToken("+919876544001");
        double before = counter("customer_profile_read_success");
        assertThat(getProfile(token).getStatusCode().value()).isEqualTo(200);
        assertThat(counter("customer_profile_read_success")).isEqualTo(before + 1);
    }

    @Test void customer_profile_update_success_increments_on_a_real_patch() {
        String token = newAccessToken("+919876544002");
        double before = counter("customer_profile_update_success");
        assertThat(patchProfile(token, "\"profile-0\"", Map.of("displayName", "X")).getStatusCode().value())
                .isEqualTo(200);
        assertThat(counter("customer_profile_update_success")).isEqualTo(before + 1);
    }

    @Test void customer_profile_update_failure_increments_with_bounded_reason_on_missing_if_match() {
        String token = newAccessToken("+919876544003");
        double before = counter("customer_profile_update_failure", "reason", "precondition_required");
        assertThat(patchProfile(token, null, Map.of("displayName", "X")).getStatusCode().value()).isEqualTo(428);
        assertThat(counter("customer_profile_update_failure", "reason", "precondition_required"))
                .isEqualTo(before + 1);
    }

    @Test void customer_profile_update_failure_increments_with_bounded_reason_on_invalid_body() {
        String token = newAccessToken("+919876544004");
        double before = counter("customer_profile_update_failure", "reason", "invalid_request");
        assertThat(patchProfile(token, "\"profile-0\"", Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(counter("customer_profile_update_failure", "reason", "invalid_request")).isEqualTo(before + 1);
    }

    @Test void precondition_failed_increments_both_the_update_failure_and_the_dedicated_counter() {
        String token = newAccessToken("+919876544005");
        patchProfile(token, "\"profile-0\"", Map.of("displayName", "First"));
        double updateFailureBefore = counter("customer_profile_update_failure", "reason", "precondition_failed");
        double dedicatedBefore = counter("customer_profile_precondition_failed");

        assertThat(patchProfile(token, "\"profile-0\"", Map.of("displayName", "Stale")).getStatusCode().value())
                .isEqualTo(412);

        assertThat(counter("customer_profile_update_failure", "reason", "precondition_failed"))
                .isEqualTo(updateFailureBefore + 1);
        assertThat(counter("customer_profile_precondition_failed")).isEqualTo(dedicatedBefore + 1);
    }

    // ---------- cardinality / privacy guard ----------

    private static final Pattern EMAIL_LIKE = Pattern.compile("@example\\.com");
    private static final Pattern CUSTOMER_ID = Pattern.compile("\\bCUS_");
    private static final Pattern REQUEST_ID = Pattern.compile("\\breq_[0-9a-f]{8,}");
    private static final Set<String> PROFILE_METER_NAMES = Set.of(
            "customer_profile_read_success", "customer_profile_read_failure",
            "customer_profile_update_success", "customer_profile_update_failure",
            "customer_profile_precondition_failed");
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("reason");

    @Test void every_profile_meter_uses_only_the_bounded_reason_vocabulary_and_no_pii_tag() {
        String token = newAccessToken("+919876544099");
        patchProfile(token, "\"profile-0\"", Map.of("displayName", "PII Name", "email", "pii@example.com"));
        getProfile(token);
        patchProfile(token, null, Map.of("displayName", "X")); // precondition_required
        patchProfile(token, "\"profile-0\"", Map.of()); // stale + empty combined -> invalid or 412
        patchProfile(token, "\"profile-1\"", Map.of("email", "not-an-email")); // invalid_request

        List<String> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!PROFILE_METER_NAMES.contains(name)) continue;
            for (Tag tag : meter.getId().getTags()) {
                String k = tag.getKey();
                String v = tag.getValue();
                if (!ALLOWED_TAG_KEYS.contains(k)) {
                    violations.add(name + " has unexpected tag key " + k);
                }
                if (EMAIL_LIKE.matcher(v).find()) violations.add(name + " tag " + k + " looks like an email: " + v);
                if (v.contains("PII Name")) violations.add(name + " tag " + k + " embeds displayName");
                if (CUSTOMER_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a customerId: " + v);
                if (REQUEST_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a requestId: " + v);
                if (k.equals("reason")) {
                    assertThat(v).matches("^[a-z_]+$");
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
