package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded JSON input: Jackson's stream-read constraints (nesting depth, number length) must turn hostile shapes into
 * the ordinary malformed-request 400, quickly, before any business code runs, and never into acceptance, an echo or
 * a stack exhaustion. Bodies stay within the 64 KiB platform limit, as an attacker's must. Exercised through the
 * product write path, whose error mapping (the global {@code ApiExceptionHandler}) is the reference behaviour.
 */
class JsonInputBoundsIT extends AbstractApiIT {

    static final String PATH = "/api/v1/products";

    private void assertRejectedAsMalformed(ResponseEntity<JsonNode> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(res.getBody().toString()).doesNotContain("[[[[").doesNotContain("99999999");
        assertThat(res.getHeaders().getFirst("X-Request-Id")).startsWith("req_");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void deeply_nested_arrays_are_a_400() {
        String body = "{\"id\":\"TZP-BOUND-1\",\"x\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}";
        assertRejectedAsMalformed(post(PATH, body, CMS_TOKEN, JsonNode.class));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void deeply_nested_objects_are_a_400() {
        String body = "{\"id\":\"TZP-BOUND-2\",\"x\":" + "{\"a\":".repeat(6_000) + "1" + "}".repeat(6_000) + "}";
        assertRejectedAsMalformed(post(PATH, body, CMS_TOKEN, JsonNode.class));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void an_over_long_number_token_is_a_400() {
        String body = "{\"id\":\"TZP-BOUND-3\",\"x\":" + "9".repeat(40_000) + "}";
        assertRejectedAsMalformed(post(PATH, body, CMS_TOKEN, JsonNode.class));
    }
}
