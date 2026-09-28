package com.tazzzo.customer.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import org.bson.Document;
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
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-12A hardening (Finding 1) — a cryptographically valid access token backed by a genuinely
 * active session does NOT, by itself, prove the referenced customer identity still exists in the
 * auth-owned {@code customers} collection (a data-integrity corruption — e.g. a manual repair gone
 * wrong, or a future customer-deletion feature that forgets to cascade). This is that scenario,
 * reproduced for real: establish a real session (a real {@code customers} document is created as
 * part of the SAME PR-11C transaction), then delete JUST the {@code customers} document underneath
 * the still-active session, and prove the profile domain refuses to fabricate or persist anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class CustomerIdentityIntegrityHttpIT extends AbstractApiIT {

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

    private JsonNode establish(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody();
    }

    /** Simulates a data-integrity corruption: the session stays genuinely active, but the customer
     *  identity record it points at is gone. */
    private void deleteCustomerIdentity(String customerId) {
        db.getCollection("customers").deleteOne(Filters.eq("_id", customerId));
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

    // ---------- A ----------

    @Test void a_get_with_a_valid_session_but_a_missing_customer_identity_is_503() {
        JsonNode established = establish("+919876566001");
        String token = established.get("accessToken").asText();
        String customerId = established.get("customerId").asText();
        deleteCustomerIdentity(customerId);

        ResponseEntity<JsonNode> res = getProfile(token);
        assertThat(res.getStatusCode().value())
                .as("a ghost identity must never look like a healthy empty profile").isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        String raw = res.getBody().toString();
        assertThat(raw).doesNotContain(customerId).doesNotContain("not found").doesNotContain("missing");
    }

    // ---------- B ----------

    @Test void b_patch_with_a_valid_session_but_a_missing_customer_identity_is_503_and_creates_no_document() {
        JsonNode established = establish("+919876566002");
        String token = established.get("accessToken").asText();
        String customerId = established.get("customerId").asText();
        deleteCustomerIdentity(customerId);

        ResponseEntity<JsonNode> res = patchProfile(token, "\"profile-0\"", Map.of("displayName", "Ghost"));
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");

        Document ghostProfile = db.getCollection("customer_profiles").find(Filters.eq("_id", customerId)).first();
        assertThat(ghostProfile).as("no profile document is ever fabricated for a missing identity").isNull();
    }

    // ---------- C: regression -- a genuinely valid identity is unaffected ----------

    @Test void c_valid_identity_with_no_profile_still_returns_the_default_projection() {
        JsonNode established = establish("+919876566003");
        String token = established.get("accessToken").asText();

        ResponseEntity<JsonNode> res = getProfile(token);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("version").asLong()).isZero();
        assertThat(res.getHeaders().getETag()).isEqualTo("\"profile-0\"");
    }

    // ---------- D: regression -- first PATCH still creates version 1 normally ----------

    @Test void d_valid_identity_first_patch_still_creates_version_one() {
        JsonNode established = establish("+919876566004");
        String token = established.get("accessToken").asText();

        ResponseEntity<JsonNode> res = patchProfile(token, "\"profile-0\"", Map.of("displayName", "Real Customer"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getETag()).isEqualTo("\"profile-1\"");
        assertThat(res.getBody().get("displayName").asText()).isEqualTo("Real Customer");
    }
}
