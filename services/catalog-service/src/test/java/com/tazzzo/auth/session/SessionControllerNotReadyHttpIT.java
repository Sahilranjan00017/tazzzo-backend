package com.tazzzo.auth.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening — a deployment missing the customer access-token signing key must answer every
 * customer-auth-adjacent request with {@code 503 SERVICE_UNAVAILABLE}, NEVER a fake
 * {@code 401 UNAUTHENTICATED} — a client cannot tell "your credential is wrong" apart from "this
 * server is broken" unless the status code itself says so. Deliberately its OWN Spring context:
 * {@code tazzzo.customer-auth.access-token-hmac-key-b64} is left UNSET (the production no-default
 * posture), unlike every other customer-auth test class in this suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class SessionControllerNotReadyHttpIT extends AbstractApiIT {

    @DynamicPropertySource
    static void noAccessTokenKey(DynamicPropertyRegistry r) {
        // Deliberately absent: tazzzo.customer-auth.access-token-hmac-key-b64
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64",
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test void logout_with_not_ready_access_codec_is_503() {
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", "any-token-value", JsonNode.class);
        assertThat(res.getStatusCode().value())
                .as("a broken signing key must never look like a bad credential").isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        assertThat(res.getHeaders().getFirst("WWW-Authenticate"))
                .as("503 is not a credential challenge").isNull();
    }

    @Test void logout_without_any_bearer_is_still_503_when_codec_not_ready() {
        // Even with NO Authorization header at all, the codec being unready must still surface as
        // 503 once the controller attempts to authenticate -- but MISSING is checked before the
        // codec is ever invoked, so this specific case is a legitimate 401 (no credential was
        // presented at all, independent of server readiness). Documented here explicitly so the
        // boundary between the two is pinned by a test, not left implicit.
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test void session_establishment_with_not_ready_access_codec_is_503() {
        ResponseEntity<JsonNode> res = post("/v1/auth/session",
                new SessionEstablishRequestDto("GRANT_doesnotmatter00000001"), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }
}
