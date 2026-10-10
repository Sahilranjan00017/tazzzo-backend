package com.tazzzo.catalog.api;

import com.tazzzo.auth.CustomerAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The servlet filter chain is a security boundary: the platform filters (request id, body-size limit, CORS) must run
 * strictly before the service-token filter and the customer filter, so an oversized body is refused before anything
 * buffers it and a CORS pre-flight is answered before authentication. Pinned here because {@code @Order} ties are
 * resolved in registration order, which is not a contract.
 */
class PlatformFilterOrderTest {

    private static int order(Class<?> filter) {
        return filter.getAnnotation(Order.class).value();
    }

    @Test
    void malformed_query_filter_runs_after_both_authentication_filters_and_the_customer_rate_limiter() {
        assertThat(order(MalformedQueryFilter.class)).isGreaterThan(order(CustomerAuthFilter.class));
        assertThat(order(MalformedQueryFilter.class)).isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Test
    void platform_filters_precede_both_authentication_filters_with_no_ties() {
        int requestId = order(RequestIdFilter.class);
        int bodyLimit = order(RequestBodyLimitFilter.class);
        int cors = Ordered.HIGHEST_PRECEDENCE + 2; // HttpPlatformConfig.corsFilter registration
        int serviceToken = order(ApiAuthFilter.class);
        int customer = order(CustomerAuthFilter.class);
        assertThat(requestId).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(bodyLimit).isGreaterThan(requestId);
        assertThat(cors).isGreaterThan(bodyLimit);
        assertThat(serviceToken).isGreaterThan(cors);
        assertThat(customer).isGreaterThan(serviceToken);
        assertThat(new java.util.HashSet<>(java.util.List.of(requestId, bodyLimit, cors, serviceToken, customer))).hasSize(5);
    }

    @Test
    void the_admin_http_metrics_filter_is_outermost_so_it_also_counts_what_the_later_filters_refuse() throws java.io.IOException {
        int metrics = order(AdminHttpMetricsFilter.class);
        // a deliberate tie with RequestIdFilter / SecurityHeadersFilter (no integer fits between them and the body limit): harmless,
        // the metrics filter neither reads nor writes the MDC or the request id
        assertThat(metrics).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(metrics).isEqualTo(order(RequestIdFilter.class));
        assertThat(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/tazzzo/catalog/api/AdminHttpMetricsFilter.java")))
                .doesNotContain("MDC").doesNotContain("REQUEST_ID");
        assertThat(metrics).isLessThan(order(RequestBodyLimitFilter.class));
        assertThat(metrics).isLessThan(order(ApiAuthFilter.class));
        assertThat(metrics).isLessThan(order(CustomerAuthFilter.class));
        assertThat(metrics).isLessThan(order(MalformedQueryFilter.class));
    }
}
