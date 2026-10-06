package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform baseline: CORS is an exact allowlist. With {@code tazzzo.http.cors.allowed-origins} set, only those origins
 * get CORS headers (no reflection, no wildcard, no credentials), a pre-flight from any other origin is refused before
 * any auth filter runs, and the response headers a browser client must read are exposed.
 */
class CorsPolicyIT extends AbstractApiIT {

    static final String ALLOWED = "https://cms.example.test";

    @DynamicPropertySource
    static void cors(DynamicPropertyRegistry r) {
        r.add("tazzzo.http.cors.allowed-origins", () -> ALLOWED + ", https://ops.example.test");
    }

    private ResponseEntity<String> preflight(String path, String origin, String method) {
        HttpHeaders h = new HttpHeaders();
        h.setOrigin(origin);
        h.setAccessControlRequestMethod(HttpMethod.valueOf(method));
        h.setAccessControlRequestHeaders(java.util.List.of("Authorization", "If-Match", "Idempotency-Key"));
        return rest.exchange(url(path), HttpMethod.OPTIONS, new HttpEntity<>(h), String.class);
    }

    @Test
    void preflight_from_an_allowed_origin_succeeds_without_credentials_and_before_authentication() {
        ResponseEntity<String> res = preflight("/api/v1/products", ALLOWED, "POST");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(res.getHeaders().getAccessControlAllowMethods()).contains(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE);
        assertThat(res.getHeaders().getAccessControlAllowHeaders()).contains("Authorization", "If-Match", "Idempotency-Key");
        assertThat(res.getHeaders().getAccessControlAllowCredentials()).isFalse();
        assertThat(res.getHeaders().getAccessControlMaxAge()).isEqualTo(600);
    }

    @Test
    void preflight_from_any_other_origin_is_refused_and_nothing_is_reflected() {
        for (String origin : new String[]{"https://evil.example.test", "https://cms.example.test.evil.example", "http://cms.example.test", "null"}) {
            ResponseEntity<String> res = preflight("/v1/customer/profile", origin, "PATCH");
            assertThat(res.getStatusCode().value()).as(origin).isEqualTo(403);
            assertThat(res.getHeaders().getAccessControlAllowOrigin()).as(origin).isNull();
        }
    }

    @Test
    void a_simple_request_from_an_allowed_origin_carries_the_exact_origin_and_the_exposed_headers() {
        HttpHeaders h = headers(READ_TOKEN);
        h.setOrigin(ALLOWED);
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/v1/admin/me"), HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
        assertThat(res.getHeaders().getAccessControlExposeHeaders()).contains("X-Request-Id", "ETag", "Retry-After");
        assertThat(res.getHeaders().getFirst("Vary")).contains("Origin");
    }

    @Test
    void a_simple_request_from_another_origin_gets_no_cors_headers() {
        HttpHeaders h = headers(null);
        h.setOrigin("https://evil.example.test");
        ResponseEntity<String> res = rest.exchange(url("/health/live"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(res.getStatusCode().value()).as("any request carrying an Origin outside the allowlist is refused").isEqualTo(403);
        assertThat(res.getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
