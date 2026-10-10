package com.tazzzo.catalog.api;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The admin HTTP meter: the route label is the matched pattern or a fixed word, never the URI; no response is changed. */
class AdminHttpMetricsTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final AdminHttpMetrics metrics = new AdminHttpMetrics(registry);
    final AdminHttpMetricsFilter filter = new AdminHttpMetricsFilter(metrics);

    /** Runs the filter; the "handler" sets the matched pattern (as Spring's mapping does) and the status, like a controller. */
    MockHttpServletResponse call(String method, String uri, String matchedPattern, int status) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> {
            if (matchedPattern != null) rq.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, matchedPattern);
            ((MockHttpServletResponse) rs).setStatus(status);
            rs.getWriter().write("body-untouched");
        };
        filter.doFilter(req, res, chain);
        return res;
    }

    long count(String surface, String route, String statusClass) {
        var t = registry.find(AdminHttpMetrics.REQUESTS).tags("surface", surface, "route", route, "status_class", statusClass).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void the_route_label_is_the_matched_pattern_not_the_uri() throws Exception {
        call("GET", "/api/v1/products/TZP-123", "/api/v1/products/{id}", 404);
        call("GET", "/api/v1/products/TZP-456", "/api/v1/products/{id}", 200);
        call("GET", "/api/v1/products/TZP-789", "/api/v1/products/{id}", 404);
        assertThat(count("internal", "/api/v1/products/{id}", "4xx")).isEqualTo(2);
        assertThat(count("internal", "/api/v1/products/{id}", "2xx")).isEqualTo(1);
        for (Meter m : registry.getMeters()) {
            for (Tag t : m.getId().getTags()) {
                assertThat(t.getValue()).doesNotContain("TZP-");
            }
        }
        assertThat(registry.find(AdminHttpMetrics.REQUESTS).timers()).hasSize(2);
    }

    @Test
    void a_request_no_handler_matched_is_the_fixed_word_unmatched_whatever_the_uri() throws Exception {
        call("GET", "/api/v1/products/TZP-123/secret-9f8e7d", null, 401);
        call("GET", "/api/v1/anything/else/" + "x".repeat(300), null, 404);
        call("POST", "/api/v1/imports/IMPJ-5f1e2d3c4b", "/**", 404);          // the catch-all resource handler is not a route either
        assertThat(count("internal", "unmatched", "4xx")).isEqualTo(3);
        assertThat(registry.find(AdminHttpMetrics.REQUESTS).timers()).hasSize(1);
    }

    @Test
    void unclassified_paths_are_recorded_as_unknown_and_bounded() throws Exception {
        call("GET", "/actuator/prometheus", null, 404);
        call("GET", "/wp-admin/../x", null, 404);
        assertThat(count("unknown", "unmatched", "4xx")).isEqualTo(2);
    }

    @Test
    void consumer_customer_and_health_requests_are_not_recorded_here() throws Exception {
        call("GET", "/catalog/v1/categories", "/catalog/v1/categories", 200);
        call("GET", "/v1/products/TZP-1", "/v1/products/{id}", 200);
        call("GET", "/v1/customer/cart", "/v1/customer/cart", 200);
        call("GET", "/health/ready", "/health/ready", 200);
        assertThat(registry.find(AdminHttpMetrics.REQUESTS).timers()).isEmpty();
    }

    @Test
    void status_classes_are_the_closed_set_and_the_response_is_never_changed() throws Exception {
        for (int status : new int[]{200, 201, 302, 400, 401, 404, 409, 422, 429, 500, 503}) {
            MockHttpServletResponse res = call("GET", "/api/v1/x", "/api/v1/x", status);
            assertThat(res.getStatus()).isEqualTo(status);
            assertThat(res.getContentAsString()).isEqualTo("body-untouched");
        }
        assertThat(AdminHttpMetrics.statusClass(99)).isEqualTo("other");
        assertThat(AdminHttpMetrics.statusClass(600)).isEqualTo("other");
        Pattern allowed = Pattern.compile("2xx|3xx|4xx|5xx|other");
        for (Meter m : registry.getMeters()) {
            for (Tag t : m.getId().getTags()) {
                if (t.getKey().equals("status_class")) assertThat(t.getValue()).matches(allowed);
                assertThat(AdminHttpMetrics.ALLOWED_TAG_KEYS).contains(t.getKey());
            }
        }
    }

    @Test
    void an_exception_escaping_the_chain_is_counted_as_5xx_and_rethrown_unchanged() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/boom");
        FilterChain chain = (rq, rs) -> {
            rq.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/boom");
            throw new ServletException("kaboom");
        };
        assertThatThrownBy(() -> filter.doFilter(req, new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class).hasMessage("kaboom");
        assertThat(count("internal", "/api/v1/boom", "5xx")).isEqualTo(1);
    }

    @Test
    void distinct_routes_are_capped_so_a_surprise_cannot_grow_the_meter_set() throws Exception {
        for (int i = 0; i < AdminHttpMetrics.MAX_ROUTES + 50; i++) {
            call("GET", "/api/v1/r" + i, "/api/v1/r" + i, 200);
        }
        assertThat(registry.find(AdminHttpMetrics.REQUESTS).timers().size()).isLessThanOrEqualTo(AdminHttpMetrics.MAX_ROUTES + 1);
        assertThat(count("internal", "other", "2xx")).isEqualTo(50);
        assertThat(count("internal", "/api/v1/r0", "2xx")).as("a route seen before the cap keeps its label").isEqualTo(1);
    }

    @Test
    void odd_pattern_attributes_never_become_labels() {
        assertThat(metrics.routeLabel(null)).isEqualTo("unmatched");
        assertThat(metrics.routeLabel(42)).isEqualTo("unmatched");
        assertThat(metrics.routeLabel("")).isEqualTo("unmatched");
        assertThat(metrics.routeLabel("no-leading-slash")).isEqualTo("other");
        assertThat(metrics.routeLabel("/api/\nx")).isEqualTo("other");
        assertThat(metrics.routeLabel("/api/" + "a".repeat(AdminHttpMetrics.MAX_ROUTE_LENGTH))).isEqualTo("other");
        assertThat(metrics.routeLabel("/api/v1/products/{id}")).isEqualTo("/api/v1/products/{id}");
    }
}
