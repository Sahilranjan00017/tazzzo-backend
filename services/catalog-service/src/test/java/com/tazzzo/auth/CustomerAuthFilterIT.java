package com.tazzzo.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.CatalogApplication;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MongoDBContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11A §20 — CustomerAuthFilter behavior over real HTTP. Uses a deliberately TINY test-only
 * probe controller under {@code /v1/customer/_probe} (never a production placeholder endpoint —
 * PR-11A creates no real customer business endpoints) purely to observe what the filter attached
 * to the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, CustomerAuthFilterIT.CustomerProbeController.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerAuthFilterIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start();
    }

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "customer-auth-fixture-key-32byte!".getBytes(StandardCharsets.UTF_8));
    static final String CMS_TOKEN = "cms-test-token";
    static final String READ_TOKEN = "read-test-token";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_customer_auth_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "DISABLED");
        r.add("tazzzo.auth.cms-token", () -> CMS_TOKEN);
        r.add("tazzzo.auth.read-token", () -> READ_TOKEN);
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> FIXTURE_KEY_B64);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired CustomerAccessTokenCodec codec;

    /** Test-only fixture — never a production customer endpoint. */
    @RestController
    static class CustomerProbeController {
        @GetMapping("/v1/customer/_probe")
        public Map<String, String> probe(HttpServletRequest request) {
            CustomerPrincipal p = CustomerPrincipalResolver.require(request);
            return Map.of("customerId", p.customerId().value(), "sessionId", p.sessionId().value());
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<JsonNode> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.set("Authorization", bearer);
        }
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> getWithHeaders(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private String validToken() {
        return codec.issue(new CustomerPrincipal(new CustomerId("CUS_alice001"), new SessionId("SES_sess0001")),
                Duration.ofMinutes(15));
    }

    // ---------- anonymous / malformed / expired ----------

    @Test void anonymous_is_401() {
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", null);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().at("/code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(res.getBody().at("/message").asText()).isEqualTo("authentication required");
        assertThat(res.getBody().at("/requestId").asText()).startsWith("req_");
    }

    @Test void bogus_bearer_is_401() {
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", "Bearer definitely-not-a-token");
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().at("/code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test void malformed_bearer_header_is_401() {
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", "NotBearer abc123");
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test void expired_token_is_401() {
        // an already-expired token, minted directly by the codec (no login endpoint exists yet)
        String expired = codec.issue(new CustomerPrincipal(new CustomerId("CUS_alice001"),
                new SessionId("SES_sess0001")), Duration.ofSeconds(-1));
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", "Bearer " + expired);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    // ---------- valid token reaches downstream ----------

    @Test void valid_customer_token_reaches_downstream_with_the_correct_principal() {
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", "Bearer " + validToken());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().at("/customerId").asText()).isEqualTo("CUS_alice001");
        assertThat(res.getBody().at("/sessionId").asText()).isEqualTo("SES_sess0001");
    }

    // ---------- cross-trust-domain rejection: service tokens never authorize customer routes ----------

    @Test void cms_service_token_does_not_authorize_the_customer_route() {
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", "Bearer " + CMS_TOKEN);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().at("/code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test void read_service_token_does_not_authorize_the_customer_route() {
        ResponseEntity<JsonNode> res = get("/v1/customer/_probe", "Bearer " + READ_TOKEN);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    // ---------- InstallationId invariant: never identity, never authorization ----------

    @Test void installation_id_alone_is_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tazzzo-Installation-Id", "forged-installation-id-claiming-to-be-a-customer");
        ResponseEntity<JsonNode> res = getWithHeaders("/v1/customer/_probe", headers);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test void valid_token_plus_arbitrary_installation_id_yields_the_same_principal() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + validToken());
        headers.set("X-Tazzzo-Installation-Id", "some-arbitrary-installation-id");
        ResponseEntity<JsonNode> res = getWithHeaders("/v1/customer/_probe", headers);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().at("/customerId").asText()).isEqualTo("CUS_alice001");
    }

    @Test void a_forged_installation_id_cannot_authorize_in_place_of_a_token() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tazzzo-Installation-Id", "CUS_alice001"); // even shaped like a customer id
        ResponseEntity<JsonNode> res = getWithHeaders("/v1/customer/_probe", headers);
        assertThat(res.getStatusCode().value())
                .as("an installation id, however it is shaped, is never authentication").isEqualTo(401);
    }

    // ---------- IP is never bound into auth identity ----------

    @Test void valid_token_plus_spoofed_x_forwarded_for_yields_the_same_principal() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + validToken());
        headers.set("X-Forwarded-For", "203.0.113.99");
        ResponseEntity<JsonNode> res = getWithHeaders("/v1/customer/_probe", headers);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().at("/customerId").asText()).isEqualTo("CUS_alice001");
    }
}
