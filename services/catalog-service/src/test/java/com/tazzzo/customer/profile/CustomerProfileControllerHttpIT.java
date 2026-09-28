package com.tazzzo.customer.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.RefreshRequestDto;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
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
 * PR-12A — {@code GET/PATCH /v1/customer/profile} exercised over real HTTP end-to-end: real
 * authentication (via the PR-11C OTP-grant -> session flow), real optimistic-concurrency headers,
 * real customer-identity isolation. Mirrors {@code SessionControllerHttpIT}'s conventions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class CustomerProfileControllerHttpIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    // Deliberately DIFFERENT from ACCESS_KEY -- the key-separation invariant rejects startup if
    // the access-token and refresh-token domains share the same secret material.
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

    private String seedGrant(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return grantId;
    }

    /** @return a fresh access token for a brand-new customer */
    private String newAccessToken(String phone) {
        String grantId = seedGrant(phone);
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

    // ---------- A/full contract: brand-new customer ----------

    @Test void a_get_on_a_brand_new_customer_returns_default_profile_version_zero() {
        String token = newAccessToken("+919876533001");
        ResponseEntity<JsonNode> res = getProfile(token);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = res.getBody();
        assertThat(body.get("displayName").isNull()).isTrue();
        assertThat(body.get("email").isNull()).isTrue();
        assertThat(body.get("version").asLong()).isZero();
        assertThat(res.getHeaders().getETag()).isEqualTo("\"profile-0\"");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    // ---------- B: unauthenticated GET ----------

    @Test void b_unauthenticated_get_is_401() {
        ResponseEntity<JsonNode> res = getProfile(null);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().get("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    // ---------- C: PATCH without If-Match ----------

    @Test void c_patch_without_if_match_is_428() {
        String token = newAccessToken("+919876533002");
        ResponseEntity<JsonNode> res = patchProfile(token, null, Map.of("displayName", "X"));
        assertThat(res.getStatusCode().value()).isEqualTo(428);
        assertThat(res.getBody().get("code").asText()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    // ---------- D/E full round trip over HTTP ----------

    @Test void d_e_patch_creates_profile_and_get_reflects_new_etag() {
        String token = newAccessToken("+919876533003");
        ResponseEntity<JsonNode> created = patchProfile(token, "\"profile-0\"",
                Map.of("displayName", "Sahil Ranjan", "email", "sahil@example.com"));
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getHeaders().getETag()).isEqualTo("\"profile-1\"");
        assertThat(created.getBody().get("displayName").asText()).isEqualTo("Sahil Ranjan");

        ResponseEntity<JsonNode> fetched = getProfile(token);
        assertThat(fetched.getHeaders().getETag()).isEqualTo("\"profile-1\"");
        assertThat(fetched.getBody().get("email").asText()).isEqualTo("sahil@example.com");
    }

    // ---------- J: empty patch ----------

    @Test void j_empty_patch_is_400() {
        String token = newAccessToken("+919876533004");
        ResponseEntity<JsonNode> res = patchProfile(token, "\"profile-0\"", Map.of());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test void j_patch_with_only_unrecognized_fields_is_400() {
        String token = newAccessToken("+919876533005");
        ResponseEntity<JsonNode> res = patchProfile(token, "\"profile-0\"", Map.of("customerId", "CUS_spoof"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ---------- malformed If-Match ----------

    @Test void malformed_if_match_is_400() {
        String token = newAccessToken("+919876533006");
        ResponseEntity<JsonNode> res = patchProfile(token, "garbage", Map.of("displayName", "X"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ---------- K/L invalid fields over HTTP ----------

    @Test void invalid_display_name_over_http_is_400() {
        String token = newAccessToken("+919876533007");
        ResponseEntity<JsonNode> res = patchProfile(token, "\"profile-0\"",
                Map.of("displayName", "x".repeat(81)));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test void invalid_email_over_http_is_400() {
        String token = newAccessToken("+919876533008");
        ResponseEntity<JsonNode> res = patchProfile(token, "\"profile-0\"", Map.of("email", "not-an-email"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ---------- M: stale If-Match over HTTP ----------

    @Test void stale_if_match_is_412_over_http() {
        String token = newAccessToken("+919876533009");
        patchProfile(token, "\"profile-0\"", Map.of("displayName", "First"));
        ResponseEntity<JsonNode> stale = patchProfile(token, "\"profile-0\"", Map.of("displayName", "Second"));
        assertThat(stale.getStatusCode().value()).isEqualTo(412);
        assertThat(stale.getBody().get("code").asText()).isEqualTo("PRECONDITION_FAILED");

        ResponseEntity<JsonNode> unchanged = getProfile(token);
        assertThat(unchanged.getBody().get("displayName").asText()).isEqualTo("First");
    }

    // ---------- P: customer A cannot access/update customer B ----------

    @Test void p_customer_a_cannot_read_or_write_customer_b_profile_by_any_means() {
        String tokenA = newAccessToken("+919876533010");
        String tokenB = newAccessToken("+919876533011");
        patchProfile(tokenA, "\"profile-0\"", Map.of("displayName", "Customer A"));
        patchProfile(tokenB, "\"profile-0\"", Map.of("displayName", "Customer B"));

        // A's own GET/PATCH always resolves to A's own profile, no matter what body/query is sent.
        ResponseEntity<JsonNode> aReadsOwn = getProfile(tokenA);
        assertThat(aReadsOwn.getBody().get("displayName").asText()).isEqualTo("Customer A");

        // Attempting to smuggle B's identity via the body has zero effect: the field does not
        // exist in the DTO/parsing contract, so it is silently ignored, never honoured.
        String aCustomerId = aReadsOwn.getBody().get("customerId").asText();
        ResponseEntity<JsonNode> spoofAttempt = patchProfile(tokenA, "\"profile-1\"",
                Map.of("displayName", "Hijacked", "customerId", "CUS_doesnotmatter"));
        assertThat(spoofAttempt.getStatusCode().value()).isEqualTo(200);
        assertThat(spoofAttempt.getBody().get("customerId").asText())
                .as("customerId in the response is ALWAYS derived from the bearer token, never the body")
                .isEqualTo(aCustomerId);

        ResponseEntity<JsonNode> bStillUnaffected = getProfile(tokenB);
        assertThat(bStillUnaffected.getBody().get("displayName").asText()).isEqualTo("Customer B");
    }

    // ---------- Q: no phoneNormalized ever leaks ----------

    @Test void q_response_never_contains_phone_normalized() {
        String token = newAccessToken("+919876533012");
        ResponseEntity<JsonNode> res = patchProfile(token, "\"profile-0\"", Map.of("displayName", "NoPhoneLeak"));
        assertThat(res.getBody().toString()).doesNotContain("9876533012");
        assertThat(res.getBody().has("phoneNormalized")).isFalse();
        assertThat(res.getBody().has("phone")).isFalse();
    }

    // ---------- R: Cache-Control no-store on success and error ----------

    @Test void r_cache_control_no_store_on_success_and_error() {
        String token = newAccessToken("+919876533013");
        assertThat(getProfile(token).getHeaders().getCacheControl()).contains("no-store");
        assertThat(patchProfile(token, null, Map.of("displayName", "X")).getHeaders().getCacheControl())
                .contains("no-store");
        assertThat(patchProfile(token, "\"profile-0\"", Map.of()).getHeaders().getCacheControl())
                .contains("no-store");
    }

    // ---------- S: ETag changes on successful mutation ----------

    @Test void s_etag_changes_on_successful_mutation() {
        String token = newAccessToken("+919876533014");
        ResponseEntity<JsonNode> before = getProfile(token);
        ResponseEntity<JsonNode> after = patchProfile(token, before.getHeaders().getETag(),
                Map.of("displayName", "Changed"));
        assertThat(after.getHeaders().getETag()).isNotEqualTo(before.getHeaders().getETag());
    }

    // ---------- refresh token continues to work independent of profile activity ----------

    @Test void profile_endpoints_do_not_interfere_with_refresh_flow() {
        String grantId = seedGrant("+919876533015");
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        String accessToken = established.get("accessToken").asText();
        String refreshToken = established.get("refreshToken").asText();

        patchProfile(accessToken, "\"profile-0\"", Map.of("displayName", "Refreshable"));

        ResponseEntity<JsonNode> refreshed = post("/v1/auth/refresh", new RefreshRequestDto(refreshToken), null,
                JsonNode.class);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
    }
}
