package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform baseline: every request body on every surface is bounded by {@code tazzzo.http.max-request-body-bytes}
 * (64 KiB here, the default). A declared length over the limit is refused before the body is read; a chunked body is
 * cut off at the first byte past the limit. Both answer 413 in the surface's own envelope; a body just under the limit
 * is still processed normally (and a malformed one is a 400, not a 413).
 */
class RequestBodyLimitIT extends AbstractApiIT {

    static final int LIMIT = 65_536;

    private static String jsonOfSize(int bytes) {
        StringBuilder sb = new StringBuilder("{\"phone\":\"");
        while (sb.length() < bytes - 2) {
            sb.append('9');
        }
        return sb.append("\"}").toString();
    }

    @Test
    void a_declared_oversized_body_on_the_public_auth_surface_is_a_flat_413() {
        ResponseEntity<JsonNode> res = post("/v1/auth/otp/request", jsonOfSize(LIMIT + 1024), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(413);
        assertThat(res.getBody().has("error")).as("flat envelope on the public surface").isFalse();
        assertThat(res.getBody().get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(res.getBody().get("message").asText()).isEqualTo("request body too large");
        assertThat(res.getBody().get("request_id").asText()).startsWith("req_");
    }

    @Test
    void a_declared_oversized_body_on_the_internal_surface_is_a_nested_413() {
        ResponseEntity<JsonNode> res = post("/api/v1/products", jsonOfSize(LIMIT + 1024), CMS_TOKEN, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(413);
        assertThat(res.getBody().at("/error/code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(res.getBody().at("/error/request_id").asText()).startsWith("req_");
    }

    @Test
    void the_limit_applies_before_authentication_so_an_anonymous_oversized_post_cannot_be_buffered() {
        ResponseEntity<JsonNode> res = post("/api/v1/products", jsonOfSize(LIMIT + 1024), null, JsonNode.class);
        assertThat(res.getStatusCode().value()).as("413, not 401: nothing is read first").isEqualTo(413);
    }

    @Test
    void a_chunked_body_without_a_declared_length_is_cut_off_past_the_limit() throws Exception {
        byte[] body = jsonOfSize(LIMIT + 4096).getBytes(StandardCharsets.UTF_8);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/v1/auth/otp/request")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))) // no Content-Length: chunked
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(413);
        JsonNode json = new ObjectMapper().readTree(res.body());
        assertThat(json.get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void a_body_under_the_limit_is_processed_and_a_malformed_one_is_400_not_413() {
        ResponseEntity<JsonNode> under = post("/v1/auth/otp/request", jsonOfSize(LIMIT - 512), null, JsonNode.class);
        assertThat(under.getStatusCode().value()).as("read fully; refused on its merits, never as too large").isNotEqualTo(413);
        ResponseEntity<JsonNode> malformed = post("/api/v1/products", "{\"title\": ", CMS_TOKEN, JsonNode.class);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(malformed.getBody().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    /** A bulk bound small enough that "over" is refused before the client has streamed much (no broken pipe). */
    static final int BULK = 128 * 1024;

    @org.springframework.test.context.DynamicPropertySource
    static void bulkBound(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("tazzzo.http.bulk-import-max-request-body-bytes", () -> String.valueOf(BULK));
    }

    @Test
    void the_admin_bulk_import_routes_take_files_up_to_their_own_bound_and_nothing_else_does() {
        String file = jsonOfSize(100 * 1024);                                    // above the API default, under the bulk bound
        assertThat(post("/api/v1/admin/imports/products", file, CMS_TOKEN, JsonNode.class).getStatusCode().value())
                .as("not refused for size (no such route on this branch, so 404/405)").isNotEqualTo(413);
        assertThat(post("/api/v1/admin/imports/products", jsonOfSize(BULK + 1024), CMS_TOKEN, JsonNode.class)
                .getStatusCode().value()).as("the bulk bound still applies").isEqualTo(413);
        assertThat(post("/api/v1/products", file, CMS_TOKEN, JsonNode.class).getStatusCode().value())
                .as("other admin routes keep the API default").isEqualTo(413);
        assertThat(post("/api/v1/admin/importsX", file, CMS_TOKEN, JsonNode.class).getStatusCode().value())
                .as("exact prefix only").isEqualTo(413);
    }

    private int chunkedPost(String path, String token, int bytes) throws Exception {
        byte[] body = jsonOfSize(bytes).getBytes(StandardCharsets.UTF_8);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));  // no Content-Length: chunked
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void a_chunked_import_is_never_read_before_authentication_and_is_bounded_while_it_is_read() throws Exception {
        assertThat(chunkedPost("/api/v1/admin/imports/products", null, 100 * 1024))
                .as("refused for missing credentials before any byte is read: 401, not 413").isEqualTo(401);
        assertThat(chunkedPost("/api/v1/admin/imports/products", CMS_TOKEN, 100 * 1024))
                .as("authenticated, under the bulk bound: not refused for size (no such route on this branch)").isNotEqualTo(413);
        assertThat(chunkedPost("/api/v1/products", CMS_TOKEN, 100 * 1024)).as("other routes keep the buffered default").isEqualTo(413);
    }

    @Test
    void the_lazy_bound_fails_exactly_past_the_limit() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest raw = new org.springframework.mock.web.MockHttpServletRequest();
        raw.setContent(new byte[10]);
        com.tazzzo.catalog.api.RequestBodyLimitFilter.LimitedStreamRequest limited = new com.tazzzo.catalog.api.RequestBodyLimitFilter.LimitedStreamRequest(raw, 9);
        jakarta.servlet.ServletInputStream in = limited.getInputStream();
        assertThat(in.read(new byte[9], 0, 9)).isEqualTo(9);
        org.assertj.core.api.Assertions.assertThatThrownBy(in::read).isInstanceOf(com.tazzzo.catalog.api.RequestBodyLimitFilter.BodyTooLargeException.class);
        com.tazzzo.catalog.api.RequestBodyLimitFilter.LimitedStreamRequest exact = new com.tazzzo.catalog.api.RequestBodyLimitFilter.LimitedStreamRequest(raw, 10);
        raw.setContent(new byte[10]);
        assertThat(exact.getInputStream().readAllBytes()).hasSize(10);
    }
}
