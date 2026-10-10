package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.api.AdminHttpMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin HTTP meter and the request-id log correlation over real HTTP: the route label is the matched Spring pattern
 * (never the URI), refusals no handler saw are the fixed word "unmatched", consumer and health requests are not recorded
 * here, and the console log line carries the same request id the response header returns.
 */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityHttpIT extends AbstractApiIT {

    static final String SECRET_PROBE = "definitely-not-a-token-AbC123xyz";
    @Autowired MeterRegistry meters;

    long count(String surface, String route, String statusClass) {
        var t = meters.find(AdminHttpMetrics.REQUESTS).tags("surface", surface, "route", route, "status_class", statusClass).timer();
        return t == null ? 0 : t.count();
    }

    long adminTotal() {
        long n = 0;
        for (var t : meters.find(AdminHttpMetrics.REQUESTS).timers()) n += t.count();
        return n;
    }

    @Test
    void admin_requests_are_counted_by_matched_pattern_and_status_class_never_by_uri() {
        long notFound = count("internal", "/api/v1/products/{id}", "4xx"), ok = count("internal", "/api/v1/products/{id}", "2xx"),
                unauth = count("internal", "unmatched", "4xx");

        ResponseEntity<JsonNode> missing = get("/api/v1/products/TZP-NOPE-123", READ_TOKEN, JsonNode.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getBody().at("/error/code").asText()).as("the response is the unchanged admin envelope").isNotBlank();
        get("/api/v1/products/TZP-NOPE-456", READ_TOKEN, JsonNode.class);
        assertThat(get("/api/v1/products/TZP-NOPE-789", null, JsonNode.class).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/v1/no/such/route/TZP-NOPE-1", READ_TOKEN, JsonNode.class).getStatusCode().value()).isEqualTo(404);

        assertThat(count("internal", "/api/v1/products/{id}", "4xx") - notFound).as("two distinct product ids, ONE route label").isEqualTo(2);
        assertThat(count("internal", "unmatched", "4xx") - unauth).as("the 401 (no handler ran) and the 404 on an unknown route").isEqualTo(2);
        assertThat(count("internal", "/api/v1/products/{id}", "2xx")).isEqualTo(ok);
    }

    @Test
    void consumer_and_health_requests_are_not_recorded_by_the_admin_meter() {
        long before = adminTotal();
        get("/health/live", null, JsonNode.class);
        get("/health/ready", null, JsonNode.class);
        get("/catalog/v1/categories", null, JsonNode.class);
        get("/v1/products/TZP-1", null, JsonNode.class);
        assertThat(adminTotal()).isEqualTo(before);
    }

    @Test
    void no_tag_value_in_the_whole_registry_is_an_id_a_uri_or_a_request_id() {
        get("/api/v1/products/TZP-GUARD-1", READ_TOKEN, JsonNode.class);
        get("/api/v1/products/TZP-GUARD-2?x=secret", null, JsonNode.class);
        Pattern route = Pattern.compile("/[A-Za-z0-9/_{}.*:-]*|unmatched|other");
        List<String> violations = new ArrayList<>();
        for (Meter m : meters.getMeters()) {
            if (m.getId().getName().startsWith("http.client.")) continue;   // the TEST's own RestTemplate, not the application
            for (Tag t : m.getId().getTags()) {
                String v = t.getValue();
                if (v.contains("TZP-") || v.contains("TZC-") || v.contains("TZV-") || v.contains("IMPJ-") || v.contains("IMP-")
                        || v.contains("req_") || v.contains(SECRET_PROBE) || v.contains("secret")) {
                    violations.add(m.getId().getName() + " " + t.getKey() + "=" + v);
                }
            }
            if (m.getId().getName().equals(AdminHttpMetrics.REQUESTS)) {
                for (Tag t : m.getId().getTags()) {
                    assertThat(AdminHttpMetrics.ALLOWED_TAG_KEYS).contains(t.getKey());
                    if (t.getKey().equals("route")) assertThat(t.getValue()).matches(route);
                    if (t.getKey().equals("surface")) assertThat(t.getValue()).isIn("internal", "unknown");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void the_console_log_line_carries_the_request_id_the_response_returns_and_the_sanitised_correlation_id(CapturedOutput out) {
        HttpHeaders h = headers(SECRET_PROBE);
        h.set("X-Correlation-Id", "corr-abc.1:x");
        ResponseEntity<JsonNode> res = rest.exchange(url("/api/v1/products/TZP-LOG-1"), HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        String requestId = res.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).startsWith("req_");
        assertThat(out.getAll()).contains("[" + requestId + ",corr-abc.1:x] ").contains("admin_auth_rejected reason=unauthenticated request_id=" + requestId);

        HttpHeaders hostile = headers(SECRET_PROBE);
        hostile.set("X-Correlation-Id", "bad value;<script>");
        ResponseEntity<JsonNode> res2 = rest.exchange(url("/api/v1/products/TZP-LOG-2"), HttpMethod.GET, new HttpEntity<>(hostile), JsonNode.class);
        String requestId2 = res2.getHeaders().getFirst("X-Request-Id");
        assertThat(out.getAll()).as("a rejected correlation id leaves the field empty and never reaches the log")
                .contains("[" + requestId2 + ",] ").doesNotContain("<script>");
        assertThat(out.getAll()).as("a credential presented to the API is never logged").doesNotContain(SECRET_PROBE);
    }
}
