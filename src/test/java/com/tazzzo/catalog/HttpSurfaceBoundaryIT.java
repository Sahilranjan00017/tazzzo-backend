package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4B — every HTTP surface is explicitly one of PUBLIC_CONSUMER / INTERNAL / UNKNOWN, and the
 * filter behaves accordingly: public bypasses authentication entirely, internal keeps today's
 * bearer rules, unknown is refused by default even to a valid internal identity (Q4-f).
 *
 * <p>No consumer controller exists yet, so the public namespace is exercised on a deliberately
 * nonexistent route. Phase 4B's property is not what that route returns; it is that ApiAuthFilter
 * never turns it into a 401 or 403, whatever credential is or is not presented.
 */
class HttpSurfaceBoundaryIT extends AbstractApiIT {

    private static final String CONSUMER_PROBE = "/catalog/v1/does-not-exist";

    private int status(String path, String token) {
        return get(path, token, String.class).getStatusCode().value();
    }

    // ---------- 1. public consumer: the header is irrelevant to authority (Q4-a) ----------

    @Test
    void consumer_namespace_bypasses_authentication_for_every_identity() {
        int anonymous = status(CONSUMER_PROBE, null);
        assertThat(anonymous).as("anonymous must not be stopped by the auth filter").isNotIn(401, 403);

        assertThat(status(CONSUMER_PROBE, "definitely-not-a-token"))
                .as("a BOGUS bearer on a public URL must not produce 401 — public != anonymous-only")
                .isEqualTo(anonymous);
        assertThat(status(CONSUMER_PROBE, READ_TOKEN))
                .as("read token confers no extra consumer authority").isEqualTo(anonymous);
        assertThat(status(CONSUMER_PROBE, CMS_TOKEN))
                .as("cms token confers no extra consumer authority").isEqualTo(anonymous);
    }

    /**
     * ERR1-FRAMEWORK-1. An unmapped PUBLIC route never reaches a consumer controller, so no advice
     * scoped to one can shape it — yet it must still be the flat consumer envelope, not the nested
     * CMS one. (Before Phase 5A hardening this test asserted the nested shape, which was the gap.)
     */
    @Test
    void an_unmapped_public_route_is_a_FLAT_404_not_the_nested_cms_envelope() {
        ResponseEntity<JsonNode> res = get(CONSUMER_PROBE, null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().has("error"))
                .as("no nested {error:{…}} on the public surface").isFalse();
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().get("message").asText()).isEqualTo("not found");
        assertThat(res.getBody().get("request_id").asText())
                .isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
    }

    @Test
    void an_unsupported_method_on_a_public_route_is_a_FLAT_400_INVALID_REQUEST() {
        ResponseEntity<JsonNode> res = post("/catalog/v1/categories", "{}", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }

    /** The internal surface is untouched by ERR1-FRAMEWORK-1: same failures, still nested. */
    @Test
    void the_equivalent_internal_failures_remain_nested() {
        ResponseEntity<JsonNode> missing = get("/api/v1/no-such-route", CMS_TOKEN, JsonNode.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().at("/error/code").asText()).isEqualTo("NO_SUCH_ENDPOINT");

        ResponseEntity<JsonNode> method = rest.exchange(url("/api/v1/taxonomy/nodes/TZS-000001"),
                org.springframework.http.HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(headers(CMS_TOKEN)), JsonNode.class);
        assertThat(method.getStatusCode().value()).isEqualTo(405);
        assertThat(method.getBody().at("/error/code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    // ---------- 6. exact namespace root ----------

    @Test
    void consumer_namespace_root_itself_is_public() {
        assertThat(status("/catalog/v1", null)).isNotIn(401, 403);
        assertThat(status("/catalog/v1", "bogus")).isEqualTo(status("/catalog/v1", null));
    }

    /** Phase 4B.1 — the retired namespace is refused, not quietly served. */
    @ParameterizedTest
    @ValueSource(strings = {"/consumer/v1", "/consumer/v1/taxonomy/root", "/consumer/v1/products/X"})
    void the_retired_consumer_namespace_is_refused_as_unknown(String path) {
        ResponseEntity<JsonNode> res = get(path, CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().value())
                .as(path + " carries no privilege after 4B.1, even for the cms identity")
                .isEqualTo(404);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("NO_SUCH_ENDPOINT");
    }

    // ---------- 2. internal: existing bearer rules exactly as today ----------

    @Test
    void api_namespace_keeps_the_existing_bearer_rules() {
        assertThat(status("/api/v1/taxonomy/nodes/TZS-000001", null)).isEqualTo(401);
        assertThat(status("/api/v1/taxonomy/nodes/TZS-000001", "bogus")).isEqualTo(401);
        assertThat(status("/api/v1/taxonomy/nodes/TZS-000001", READ_TOKEN))
                .as("reader GET: existing behaviour (never an auth refusal)").isNotIn(401, 403);
        assertThat(post("/api/v1/products", "{}", READ_TOKEN, String.class).getStatusCode().value())
                .as("reader write").isEqualTo(403);
        assertThat(post("/api/v1/products", "{}", CMS_TOKEN, String.class).getStatusCode().value())
                .as("cms writer reaches the controller (whatever it says about an empty body)")
                .isNotIn(401, 403, 404);
    }

    // ---------- 3. OpenAPI: exactly as Phase 4A ----------

    @Test
    void openapi_remains_protected_exactly_as_phase_4a() {
        assertThat(status("/v3/api-docs", null)).isEqualTo(401);
        assertThat(status("/v3/api-docs", "bogus")).isEqualTo(401);
        assertThat(status("/v3/api-docs", READ_TOKEN)).isEqualTo(200);
        assertThat(status("/v3/api-docs", CMS_TOKEN)).isEqualTo(200);
        assertThat(status("/v3/api-docs.yaml", null)).isEqualTo(401);
    }

    // ---------- 4. unknown: refused by default, even to a valid internal identity (Q4-f) ----------

    @ParameterizedTest
    @ValueSource(strings = {"/definitely-not-a-surface", "/", "/actuator/health", "/error"})
    void unknown_surfaces_are_404_NO_SUCH_ENDPOINT_for_every_identity(String path) {
        for (String token : new String[]{null, "bogus", READ_TOKEN, CMS_TOKEN}) {
            ResponseEntity<JsonNode> res = get(path, token, JsonNode.class);
            assertThat(res.getStatusCode().value())
                    .as(path + " with token=" + token + " is not a surface").isEqualTo(404);
            assertThat(res.getBody().at("/error/code").asText())
                    .as("the service's existing internal convention, nested envelope")
                    .isEqualTo("NO_SUCH_ENDPOINT");
            assertThat(res.getBody().at("/error/request_id").isTextual()).isTrue();
        }
    }

    // ---------- 5. near-miss consumer namespaces are UNKNOWN, never public ----------

    @ParameterizedTest
    @ValueSource(strings = {"/catalog", "/catalog/", "/catalog/v1x/foo", "/catalog/v10/foo",
            "/catalog/v2/foo", "/catalog-public/foo"})
    void near_miss_consumer_namespaces_are_refused_as_unknown(String path) {
        ResponseEntity<JsonNode> res = get(path, CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().value())
                .as(path + " must be UNKNOWN even to the cms identity").isEqualTo(404);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("NO_SUCH_ENDPOINT");
    }
}
