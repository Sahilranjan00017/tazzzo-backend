package com.tazzzo.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.CatalogApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-11A §16/§17/§22 — customer and service credentials are COMPLETELY SEPARATE trust domains.
 * A customer access token must confer no extra privilege on the public commerce surface, and must
 * never authorize the internal {@code /api/**} surface; the existing internal bearer rules remain
 * exactly as before this PR.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossSurfaceAuthIsolationIT {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start();
    }

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "cross-surface-fixture-key-32byte".getBytes(StandardCharsets.UTF_8));
    static final String CMS_TOKEN = "cms-test-token";
    static final String READ_TOKEN = "read-test-token";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_cross_surface_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "DISABLED");
        r.add("tazzzo.auth.cms-token", () -> CMS_TOKEN);
        r.add("tazzzo.auth.read-token", () -> READ_TOKEN);
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> FIXTURE_KEY_B64);
        // tazzzo.freshness.enabled deliberately UNSET -> category-products fails closed regardless
        // of auth header, which is exactly the "authority-independent" property under test.
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired CustomerAccessTokenCodec codec;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private int status(String path, String authorizationHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorizationHeader != null) {
            headers.set("Authorization", authorizationHeader);
        }
        ResponseEntity<JsonNode> res = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);
        return res.getStatusCode().value();
    }

    private String customerToken() {
        return "Bearer " + codec.issue(
                new CustomerPrincipal(new CustomerId("CUS_isolationtest"), new SessionId("SES_isolationtest")),
                Duration.ofMinutes(5));
    }

    // ---------- §16: public commerce reads stay anonymous / authority-independent ----------

    @Test void categories_status_is_identical_regardless_of_auth_header() {
        int anonymous = status("/v1/categories", null);
        assertThat(anonymous).as("must not be an auth refusal").isNotIn(401, 403);
        assertThat(status("/v1/categories", "Bearer bogus-token")).isEqualTo(anonymous);
        assertThat(status("/v1/categories", customerToken()))
                .as("a customer access token confers no extra privilege on a public route")
                .isEqualTo(anonymous);
    }

    @Test void children_status_is_identical_regardless_of_auth_header() {
        String path = "/v1/categories/TZC-000001/children";
        int anonymous = status(path, null);
        assertThat(status(path, "Bearer bogus-token")).isEqualTo(anonymous);
        assertThat(status(path, customerToken())).isEqualTo(anonymous);
    }

    @Test void category_products_status_is_identical_regardless_of_auth_header() {
        String path = "/v1/categories/TZC-000001/products";
        int anonymous = status(path, null);
        assertThat(status(path, "Bearer bogus-token")).isEqualTo(anonymous);
        assertThat(status(path, customerToken()))
                .as("customer token must not grant list access to an otherwise-gated route")
                .isEqualTo(anonymous);
    }

    @Test void product_detail_status_is_identical_regardless_of_auth_header() {
        String path = "/v1/products/TZP-DOES-NOT-EXIST";
        int anonymous = status(path, null);
        assertThat(status(path, "Bearer bogus-token")).isEqualTo(anonymous);
        assertThat(status(path, customerToken())).isEqualTo(anonymous);
    }

    @Test void serviceability_status_is_identical_regardless_of_auth_header() {
        String path = "/v1/serviceability?pin=560001";
        int anonymous = status(path, null);
        assertThat(status(path, "Bearer bogus-token")).isEqualTo(anonymous);
        assertThat(status(path, customerToken())).isEqualTo(anonymous);
    }

    // ---------- §17: internal /api/** isolation is untouched, and a customer token never authorizes it ----------

    @Test void no_service_token_on_internal_api_is_still_401() {
        assertThat(status("/api/v1/taxonomy/nodes/TZS-000001", null)).isEqualTo(401);
    }

    @Test void bad_service_token_on_internal_api_is_still_401() {
        assertThat(status("/api/v1/taxonomy/nodes/TZS-000001", "Bearer bogus")).isEqualTo(401);
    }

    @Test void read_service_token_on_internal_api_is_unchanged() {
        assertThat(status("/api/v1/taxonomy/nodes/TZS-000001", "Bearer " + READ_TOKEN)).isNotIn(401, 403);
    }

    @Test void customer_access_token_never_authorizes_the_internal_api() {
        assertThat(status("/api/v1/taxonomy/nodes/TZS-000001", customerToken()))
                .as("customer and service credentials are separate trust domains").isEqualTo(401);
    }

    @Test void customer_access_token_never_authorizes_a_cms_write() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", customerToken());
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/v1/products"), HttpMethod.POST,
                new HttpEntity<>("{}", headers), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }
}
