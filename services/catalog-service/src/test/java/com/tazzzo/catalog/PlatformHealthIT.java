package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform baseline: liveness and readiness over real HTTP. The test context starts the application exactly as a
 * serving instance does (migration mode LEGACY in tests: the serving gate ends OPEN), with the rate limiter in its
 * fail-closed DISABLED mode.
 */
class PlatformHealthIT extends AbstractApiIT {

    @Test
    void live_is_200_UP_and_never_cached() {
        ResponseEntity<JsonNode> res = get("/health/live", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("status").asText()).isEqualTo("UP");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        assertThat(res.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
    }

    @Test
    void ready_is_200_with_bounded_component_words_only() {
        ResponseEntity<JsonNode> res = get("/health/ready", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = res.getBody();
        assertThat(body.get("status").asText()).isEqualTo("UP");
        assertThat(body.at("/components/datastore").asText()).isEqualTo("OPEN");
        assertThat(body.at("/components/mongo").asText()).isEqualTo("UP");
        assertThat(body.at("/components/rate_limiter").asText()).as("DISABLED mode is reported, not failed").isEqualTo("DISABLED");
        String text = body.toString();
        assertThat(text).doesNotContain("mongodb://").doesNotContain("localhost").doesNotContain("27017")
                .doesNotContain("redis").doesNotContain("Exception");
        assertThat(body.fieldNames()).toIterable().containsExactly("status", "components");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void probes_ignore_credentials_entirely() {
        assertThat(get("/health/live", "definitely-not-a-token", JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/health/ready", CMS_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void probes_are_GET_only() {
        ResponseEntity<JsonNode> res = post("/health/ready", "{}", null, JsonNode.class);
        assertThat(res.getStatusCode().value()).as("405 collapses to the flat 400 on a non-internal surface").isEqualTo(400);
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
    }
}
