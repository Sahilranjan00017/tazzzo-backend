package com.tazzzo.auth.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.auth.CustomerAccessTokenCodec;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.CustomerPrincipalResolver;
import com.tazzzo.auth.SessionId;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11C hardening (Finding 2) — proves the minimum bounded session/auth metrics actually exist,
 * increment on the right outcomes, and NEVER carry a high-cardinality/identity tag value. Mirrors
 * {@code ConsumerObservabilityIT}'s conventions.
 *
 * <p>{@code session_auth_rejected} has TWO independent producers — {@code CustomerAuthFilter} (any
 * {@code CUSTOMER_AUTHENTICATED} route) and {@code SessionExceptionHandler} (logout's own inline
 * bearer check). A test-only probe controller under {@code /v1/customer/_probe} (mirrors
 * {@code CustomerAuthFilterIT}'s fixture — never a production endpoint) exercises the FIRST
 * producer directly; logout exercises the second.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, SessionObservabilityIT.CustomerProbeController.class})
class SessionObservabilityIT extends AbstractApiIT {

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
    @Autowired MeterRegistry registry;
    @Autowired CustomerAccessTokenCodec accessCodec;
    @Autowired CustomerSessionRepository sessions;

    /** Test-only fixture — never a production customer endpoint (mirrors CustomerAuthFilterIT). */
    @RestController
    static class CustomerProbeController {
        @GetMapping("/v1/customer/_probe")
        public Map<String, String> probe(HttpServletRequest request) {
            CustomerPrincipal p = CustomerPrincipalResolver.require(request);
            return Map.of("customerId", p.customerId().value(), "sessionId", p.sessionId().value());
        }
    }

    private String seedGrant(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        return grantId;
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test void session_create_success_increments_on_a_real_session_establishment() {
        double before = counter("session_create_success");
        String grantId = seedGrant("+919876522001");
        ResponseEntity<JsonNode> res = post("/v1/auth/session",
                new SessionEstablishRequestDto(grantId), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(counter("session_create_success")).isEqualTo(before + 1);
    }

    @Test void session_create_failure_increments_with_a_bounded_reason_on_an_unknown_grant() {
        double before = counter("session_create_failure", "reason", "invalid");
        ResponseEntity<JsonNode> res = post("/v1/auth/session",
                new SessionEstablishRequestDto("GRANT_doesnotexist12345"), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("session_create_failure", "reason", "invalid")).isEqualTo(before + 1);
    }

    @Test void refresh_success_and_refresh_failure_increment_correctly() {
        String grantId = seedGrant("+919876522002");
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        String refreshToken = established.get("refreshToken").asText();

        double successBefore = counter("refresh_success");
        ResponseEntity<JsonNode> refreshed = post("/v1/auth/refresh", new RefreshRequestDto(refreshToken), null,
                JsonNode.class);
        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        assertThat(counter("refresh_success")).isEqualTo(successBefore + 1);

        // reusing the now-rotated-away token is a bounded INVALID failure
        double failureBefore = counter("refresh_failure", "reason", "invalid");
        ResponseEntity<JsonNode> reused = post("/v1/auth/refresh", new RefreshRequestDto(refreshToken), null,
                JsonNode.class);
        assertThat(reused.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("refresh_failure", "reason", "invalid")).isEqualTo(failureBefore + 1);
    }

    @Test void logout_increments_on_a_real_revocation() {
        String grantId = seedGrant("+919876522003");
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        String accessToken = established.get("accessToken").asText();

        double before = counter("logout");
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", accessToken, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(204);
        assertThat(counter("logout")).isEqualTo(before + 1);
    }

    // ---------- session_auth_rejected — PRODUCER A: SessionExceptionHandler (logout's inline auth) ----------

    @Test void session_exception_handler_producer_increments_session_auth_rejected_on_missing_bearer() {
        double before = counter("session_auth_rejected", "reason", "missing");
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("session_auth_rejected", "reason", "missing")).isEqualTo(before + 1);
    }

    @Test void session_exception_handler_producer_increments_session_auth_rejected_on_bad_bearer() {
        double before = counter("session_auth_rejected", "reason", "malformed");
        ResponseEntity<JsonNode> res = post("/v1/auth/logout", "", "definitely-not-a-real-token", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("session_auth_rejected", "reason", "malformed")).isEqualTo(before + 1);
    }

    // ---------- session_auth_rejected — PRODUCER B: CustomerAuthFilter (any CUSTOMER_AUTHENTICATED route) ----------

    @Test void customer_auth_filter_producer_increments_session_auth_rejected_on_missing_bearer() {
        double before = counter("session_auth_rejected", "reason", "missing");
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("session_auth_rejected", "reason", "missing")).isEqualTo(before + 1);
    }

    @Test void customer_auth_filter_producer_increments_session_auth_rejected_on_bad_bearer() {
        double before = counter("session_auth_rejected", "reason", "malformed");
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", "definitely-not-a-real-token", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("session_auth_rejected", "reason", "malformed")).isEqualTo(before + 1);
    }

    @Test void customer_auth_filter_producer_increments_session_auth_rejected_on_revoked_session() {
        SessionId sessionId = SessionId.generate();
        CustomerId customerId = CustomerId.generate();
        sessions.create(sessionId, customerId, Instant.now(), Instant.now().plusSeconds(3600),
                "fixture-digest-unused-by-filter".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sessions.revoke(sessionId.value(), Instant.now());
        String token = accessCodec.issue(new CustomerPrincipal(customerId, sessionId), Duration.ofMinutes(15));

        double before = counter("session_auth_rejected", "reason", "session_inactive");
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", token, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(counter("session_auth_rejected", "reason", "session_inactive")).isEqualTo(before + 1);
    }

    // ---------- cardinality / privacy guard over every session/auth meter ----------

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern REQUEST_ID = Pattern.compile("\\breq_[0-9a-f]{8,}");
    private static final Pattern CUSTOMER_ID = Pattern.compile("\\bCUS_");
    private static final Pattern SESSION_ID = Pattern.compile("\\bSES_");
    private static final Pattern GRANT_ID = Pattern.compile("\\bGRANT_");
    private static final Set<String> SESSION_METER_NAMES = Set.of(
            "session_create_success", "session_create_failure", "refresh_success", "refresh_failure",
            "logout", "session_auth_rejected");
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("reason");

    @Test void every_session_meter_uses_only_the_bounded_reason_vocabulary_and_no_identity_tag() {
        // Drive every code path at least once so every meter of interest actually exists.
        String grantId = seedGrant("+919876522099");
        JsonNode established = post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null,
                JsonNode.class).getBody();
        String accessToken = established.get("accessToken").asText();
        String refreshToken = established.get("refreshToken").asText();
        post("/v1/auth/session", new SessionEstablishRequestDto("GRANT_bogus000000000001"), null, JsonNode.class);
        post("/v1/auth/refresh", new RefreshRequestDto(refreshToken), null, JsonNode.class);
        post("/v1/auth/refresh", new RefreshRequestDto(refreshToken), null, JsonNode.class); // now-stale -> failure
        post("/v1/auth/logout", "", "bogus-token-value", JsonNode.class);
        post("/v1/auth/logout", "", accessToken, JsonNode.class);

        List<String> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!SESSION_METER_NAMES.contains(name)) continue;
            for (Tag tag : meter.getId().getTags()) {
                String k = tag.getKey();
                String v = tag.getValue();
                if (!ALLOWED_TAG_KEYS.contains(k)) {
                    violations.add(name + " has unexpected tag key " + k);
                }
                if (IPV4.matcher(v).find()) violations.add(name + " tag " + k + " looks like an IP: " + v);
                if (REQUEST_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a requestId: " + v);
                if (CUSTOMER_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a customerId: " + v);
                if (SESSION_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a sessionId: " + v);
                if (GRANT_ID.matcher(v).find()) violations.add(name + " tag " + k + " looks like a grantId: " + v);
                if (v.contains(accessToken) || v.contains(refreshToken)) {
                    violations.add(name + " tag " + k + " embeds a raw token");
                }
                // every reason value must be lower_snake_case coming from a bounded enum — never a
                // raw exception class name (which would be CamelCase, e.g. "MongoException").
                if (k.equals("reason")) {
                    assertThat(v).matches("^[a-z_]+$");
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
