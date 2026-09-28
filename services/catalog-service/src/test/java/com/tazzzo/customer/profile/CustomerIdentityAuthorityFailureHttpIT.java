package com.tazzzo.customer.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
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
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12A hardening (Finding 1) — {@link CustomerIdentityAuthority} itself failing (e.g. its
 * backing Mongo query throwing) must be a controlled {@code 503}, never a fake {@code 401}, never
 * a fabricated default profile, and never a leaked exception detail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CustomerIdentityAuthorityFailureHttpIT.TestBeans.class})
class CustomerIdentityAuthorityFailureHttpIT extends AbstractApiIT {

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
        CustomerIdentityAuthority alwaysFailingIdentityAuthority() {
            return (CustomerId customerId) -> {
                throw new RuntimeException("simulated identity-authority outage pii@example.com CUS_sensitive");
            };
        }
    }

    @Autowired OtpVerifiedGrantRepository grants;

    private String newAccessToken(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        return established.get("accessToken").asText();
    }

    @Test void get_maps_an_identity_authority_failure_to_503_without_leaking_details() {
        String token = newAccessToken("+919876577001");
        ResponseEntity<JsonNode> res = rest.exchange(url("/v1/customer/profile"), HttpMethod.GET,
                new HttpEntity<>(headers(token)), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        String raw = res.getBody().toString();
        assertThat(raw).doesNotContain("RuntimeException").doesNotContain("pii@example.com")
                .doesNotContain("CUS_sensitive").doesNotContain("simulated identity-authority outage");
    }

    @Test void patch_maps_an_identity_authority_failure_to_503_without_leaking_details() {
        String token = newAccessToken("+919876577002");
        HttpHeaders h = headers(token);
        h.set("If-Match", "\"profile-0\"");
        ResponseEntity<JsonNode> res = rest.exchange(url("/v1/customer/profile"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("displayName", "X"), h), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        String raw = res.getBody().toString();
        assertThat(raw).doesNotContain("RuntimeException").doesNotContain("pii@example.com")
                .doesNotContain("CUS_sensitive");
    }
}
