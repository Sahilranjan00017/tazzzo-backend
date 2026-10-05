package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every surface and every outcome carries the defensive headers: success, auth refusals written by filters, and 404. */
class SecurityHeadersIT extends AbstractApiIT {

    static final Map<String, String> EXPECTED = Map.of(
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY",
            "Referrer-Policy", "no-referrer",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            "Strict-Transport-Security", "max-age=31536000; includeSubDomains",
            "Cross-Origin-Resource-Policy", "same-site",
            "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");

    void assertHeaders(String what, ResponseEntity<?> res) {
        HttpHeaders h = res.getHeaders();
        EXPECTED.forEach((name, value) -> assertThat(h.get(name)).as(what + " " + name).containsExactly(value));
        assertThat(h.getFirst("Server")).as(what + " Server").isNull();
        assertThat(h.getFirst("X-Powered-By")).as(what + " X-Powered-By").isNull();
    }

    @Test
    void every_outcome_on_every_surface_carries_the_headers() {
        assertHeaders("admin 200", get("/api/v1/taxonomy/releases", READ_TOKEN, JsonNode.class));
        ResponseEntity<JsonNode> unauth = get("/api/v1/taxonomy/releases", null, JsonNode.class);
        assertThat(unauth.getStatusCode().value()).isEqualTo(401);
        assertHeaders("admin 401 (written by a filter)", unauth);
        ResponseEntity<JsonNode> customer = get("/v1/customer/profile", null, JsonNode.class);
        assertThat(customer.getStatusCode().value()).isEqualTo(401);
        assertHeaders("customer 401 (written by a filter)", customer);
        assertHeaders("unknown surface", get("/nope/at/all", null, String.class));
        assertHeaders("public read", get("/v1/categories", null, String.class));
    }
}
