package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4A / Q4-e — the internal OpenAPI document is no longer an anonymous public surface.
 *
 * <p>Before this slice the generated spec — a map of every CMS write endpoint — was served with no
 * credential at all, and a passing test asserted it. It now sits inside the EXISTING service-token
 * boundary: the read and cms identities may fetch it, nothing else may. No new credential, no
 * consumer token, no IP rule. The error envelope is the existing internal nested one, because
 * OpenAPI is an internal surface and not /consumer/**.
 */
class OpenApiProtectionIT extends AbstractApiIT {

    // ---------- anonymous and unknown identities are refused ----------

    @Test
    void anonymous_api_docs_is_401_with_the_internal_envelope() {
        ResponseEntity<JsonNode> res = get("/v3/api-docs", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        JsonNode body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.at("/error/code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.at("/error/message").isTextual()).isTrue();
        assertThat(body.at("/error/request_id").isTextual())
                .as("the existing nested internal envelope, not ERR-1's flat consumer one")
                .isTrue();
    }

    @Test
    void a_bogus_bearer_is_401() {
        ResponseEntity<JsonNode> res = get("/v3/api-docs", "not-a-real-token", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void sub_paths_and_the_yaml_variant_are_inside_the_boundary() {
        assertThat(get("/v3/api-docs/swagger-config", null, String.class).getStatusCode().value())
                .isEqualTo(401);
        assertThat(get("/v3/api-docs.yaml", null, String.class).getStatusCode().value())
                .isEqualTo(401);
    }

    // ---------- the two known internal identities may read it ----------

    @Test
    void read_token_gets_a_valid_openapi_document() {
        ResponseEntity<JsonNode> res = get("/v3/api-docs", READ_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode spec = res.getBody();
        assertThat(spec.has("openapi")).as("an OpenAPI document declares its version").isTrue();
        assertThat(spec.at("/paths").size()).isGreaterThanOrEqualTo(12);
        assertThat(spec.at("/paths").has("/api/v1/products/{id}/classify"))
                .as("this is the document that maps the CMS write surface").isTrue();
    }

    @Test
    void cms_token_gets_the_document_too() {
        assertThat(get("/v3/api-docs", CMS_TOKEN, JsonNode.class).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void the_yaml_variant_is_served_to_an_authenticated_reader() {
        ResponseEntity<String> res = get("/v3/api-docs.yaml", READ_TOKEN, String.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("openapi:");
    }

    // ---------- the existing /api/** boundary is untouched ----------

    @Test
    void existing_api_boundary_is_unchanged() {
        assertThat(get("/api/v1/taxonomy/nodes/TZS-000001", null, String.class)
                .getStatusCode().value()).as("anonymous /api read is still 401").isEqualTo(401);
        assertThat(get("/api/v1/taxonomy/nodes/TZS-000001", "bogus", String.class)
                .getStatusCode().value()).isEqualTo(401);
        int readGet = get("/api/v1/taxonomy/nodes/TZS-000001", READ_TOKEN, String.class)
                .getStatusCode().value();
        assertThat(readGet).as("reader may GET (404 if unseeded is fine; never 401/403)")
                .isNotIn(401, 403);
        assertThat(post("/api/v1/products", "{}", READ_TOKEN, String.class)
                .getStatusCode().value()).as("reader still may not write").isEqualTo(403);
    }

    // ---------- the Phase-4 tripwire has GRADUATED ----------

    // The temporary "no /catalog/v1 controller yet" source guard lived here. Phase 5A added the
    // first consumer controller, which is exactly what it existed to notice. It is REPLACED by
    // ConsumerTransportGuardIT's Q4-c boundary assertions, not deleted — retiring a protection
    // without a successor removes it.
}
