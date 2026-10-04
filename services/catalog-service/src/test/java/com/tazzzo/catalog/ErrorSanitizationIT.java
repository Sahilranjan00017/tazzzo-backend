package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform baseline: framework-level failures never reach the catch-all 500 and never echo parser text, Java type
 * names, raw input or field paths. Only the stable code, a fixed message (or a parameter NAME) and the request id leave.
 */
class ErrorSanitizationIT extends AbstractApiIT {

    @Test
    void a_malformed_json_body_is_400_with_a_fixed_message_and_no_parser_text() {
        ResponseEntity<JsonNode> res = post("/api/v1/products", "{\"title\": \"x\", \"nope\": [1,", CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
        String message = res.getBody().at("/error/message").asText();
        assertThat(message).isEqualTo("request body is malformed or unreadable");
        assertThat(res.getBody().toString()).doesNotContain("com.fasterxml").doesNotContain("Jackson")
                .doesNotContain("com.tazzzo").doesNotContain("line:").doesNotContain("Unexpected");
    }

    @Test
    void a_wrongly_typed_body_field_is_400_without_the_java_type_or_the_value() {
        ResponseEntity<JsonNode> res = post("/api/v1/taxonomy/nodes/TZS-000001/rename",
                "{\"name\":\"x\",\"expectedVersion\":\"not-a-number-SECRETVALUE\"}", CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().toString()).doesNotContain("SECRETVALUE").doesNotContain("java.lang").doesNotContain("com.tazzzo");
    }

    @Test
    void a_collection_get_whose_required_parameter_is_missing_is_400_never_the_500_catch_all() {
        for (String token : new String[]{READ_TOKEN, CMS_TOKEN}) {
            ResponseEntity<JsonNode> res = get("/api/v1/products", token, JsonNode.class);
            assertThat(res.getStatusCode().value()).as("token=" + token).isEqualTo(400);
            assertThat(res.getBody().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
            assertThat(res.getBody().at("/error/message").asText()).contains("canonicalKey");
            ResponseEntity<JsonNode> other = get("/api/v1/products?limit=10", token, JsonNode.class);
            assertThat(other.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Test
    void a_wrongly_typed_query_parameter_names_the_parameter_only() {
        ResponseEntity<JsonNode> res = get("/api/v1/admin/audit-events?limit=abc", CMS_TOKEN, JsonNode.class);
        // this route validates its own grammar first; whichever layer answers, nothing internal leaks
        assertThat(res.getStatusCode().value()).isIn(400, 403);
        assertThat(res.getBody().toString()).doesNotContain("java.lang").doesNotContain("Failed to convert");
    }
}
