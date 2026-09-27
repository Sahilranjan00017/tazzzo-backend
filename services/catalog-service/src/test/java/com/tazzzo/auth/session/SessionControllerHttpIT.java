package com.tazzzo.auth.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11C — {@code /v1/auth/session}, {@code /v1/auth/refresh}, {@code /v1/auth/logout} exercised
 * over real HTTP (mirrors {@code OtpControllerHttpIT}/{@code HttpSurfaceBoundaryIT} conventions).
 * Proves: session/refresh are reachable unauthenticated (as they must be), logout is NOT (§21's
 * explicit required invariant), responses never leak the phone/session internals, and no-store on
 * every response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class SessionControllerHttpIT extends AbstractApiIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    // Deliberately DIFFERENT from ACCESS_KEY — Finding 3's key-separation invariant rejects
    // startup if the access-token and refresh-token domains share the same secret material.
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
    // AbstractApiIT.CMS_TOKEN is package-private in com.tazzzo.catalog; same literal value it
    // configures tazzzo.auth.cms-token to.
    static final String CMS_TOKEN = "cms-test-token";

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

    // ---------- session establishment ----------

    @Test void session_establishment_is_reachable_unauthenticated() {
        String grantId = seedGrant("+919876511001");
        ResponseEntity<JsonNode> res = post("/v1/auth/session",
                new SessionEstablishRequestDto(grantId), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        JsonNode body = res.getBody();
        assertThat(body.has("customerId")).isTrue();
        assertThat(body.has("accessToken")).isTrue();
        assertThat(body.has("refreshToken")).isTrue();
        assertThat(body.get("accessTokenExpiresIn").asLong()).isEqualTo(900);
        assertThat(body.has("sessionId")).as("sessionId is deliberately never exposed").isFalse();
        assertThat(body.has("phoneNormalized")).isFalse();
        assertThat(body.toString()).doesNotContain("9876511001");
    }

    @Test void unknown_grant_is_401() {
        ResponseEntity<JsonNode> res = post("/v1/auth/session",
                new SessionEstablishRequestDto("GRANT_doesnotexist"), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(res.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void malformed_grant_shape_is_400() {
        ResponseEntity<JsonNode> res = post("/v1/auth/session",
                new SessionEstablishRequestDto(""), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    @Test void session_establishment_authority_headers_confer_no_special_behavior() {
        String grantId = seedGrant("+919876511002");
        int anonymous = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class)
                .getStatusCode().value();
        String grantId2 = seedGrant("+919876511003");
        int withBogusBearer = post("/v1/auth/session", new SessionEstablishRequestDto(grantId2), CMS_TOKEN,
                JsonNode.class).getStatusCode().value();
        assertThat(withBogusBearer).isEqualTo(anonymous);
    }

    // ---------- refresh ----------

    @Test void refresh_round_trip_over_http() {
        String grantId = seedGrant("+919876511010");
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        String refreshToken = established.get("refreshToken").asText();

        ResponseEntity<JsonNode> refreshed = post("/v1/auth/refresh", new RefreshRequestDto(refreshToken), null,
                JsonNode.class);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        assertThat(refreshed.getHeaders().getCacheControl()).contains("no-store");
        assertThat(refreshed.getBody().has("refreshToken")).isTrue();
        assertThat(refreshed.getBody().has("accessToken")).isTrue();

        // the OLD refresh token no longer works
        ResponseEntity<JsonNode> reused = post("/v1/auth/refresh", new RefreshRequestDto(refreshToken), null,
                JsonNode.class);
        assertThat(reused.getStatusCode().value()).isEqualTo(401);
    }

    @Test void malformed_refresh_token_is_400() {
        ResponseEntity<JsonNode> res = post("/v1/auth/refresh", new RefreshRequestDto("not-a-token"), null,
                JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    // ---------- logout: the ONE endpoint on this public surface requiring authentication ----------

    @Test void logout_without_bearer_token_is_401() {
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", null, JsonNode.class);
        assertThat(res.getStatusCode().value())
                .as("§21 — logout must NOT be reachable unauthenticated just because /v1/auth/** is public")
                .isEqualTo(401);
        assertThat(res.getBody().get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(res.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void logout_with_bogus_bearer_is_401() {
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", "definitely-not-a-token", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test void logout_with_service_token_is_401() {
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().value())
                .as("a CMS/read service token must never authorize customer logout").isEqualTo(401);
    }

    @Test void logout_with_valid_token_revokes_the_session_and_refresh_stops_working() {
        String grantId = seedGrant("+919876511020");
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        String accessToken = established.get("accessToken").asText();
        String refreshToken = established.get("refreshToken").asText();

        ResponseEntity<JsonNode> logout = post("/v1/auth/logout", "", accessToken, JsonNode.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);
        assertThat(logout.getHeaders().getCacheControl()).contains("no-store");

        ResponseEntity<JsonNode> refreshAfterLogout = post("/v1/auth/refresh", new RefreshRequestDto(refreshToken),
                null, JsonNode.class);
        assertThat(refreshAfterLogout.getStatusCode().value()).isEqualTo(401);
    }

    @Test void repeated_logout_is_safe() {
        String grantId = seedGrant("+919876511030");
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        String accessToken = established.get("accessToken").asText();
        assertThat(post("/v1/auth/logout", "", accessToken, JsonNode.class).getStatusCode().value()).isEqualTo(204);
        assertThat(post("/v1/auth/logout", "", accessToken, JsonNode.class).getStatusCode().value()).isEqualTo(204);
    }

    // ---------- §22: public commerce route safety is unaffected by this PR ----------

    @Test void public_commerce_routes_remain_safe_method_only_and_unauthenticated() {
        assertThat(status("/v1/categories", null)).isNotIn(401, 403);
        ResponseEntity<JsonNode> postAttempt = post("/v1/categories", "{}", null, JsonNode.class);
        assertThat(postAttempt.getStatusCode().value())
                .as("no accidental write capability was introduced on the public commerce surface")
                .isEqualTo(400);
    }

    private int status(String path, String token) {
        return get(path, token, String.class).getStatusCode().value();
    }
}
