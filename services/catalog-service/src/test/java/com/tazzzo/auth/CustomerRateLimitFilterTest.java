package com.tazzzo.auth;

import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.BucketDimension;
import com.tazzzo.catalog.ratelimit.BucketSpec;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The filter's decisions and the properties' all-or-nothing rule, without Spring or Redis. */
class CustomerRateLimitFilterTest {

    static CustomerRateLimitProperties props(long rc, double rr, long wc, double wr) {
        CustomerRateLimitProperties p = new CustomerRateLimitProperties();
        p.getReads().setCapacity(rc);
        p.getReads().setRefillPerSecond(rr);
        p.getWrites().setCapacity(wc);
        p.getWrites().setRefillPerSecond(wr);
        return p;
    }

    static final class Recording implements RateLimitStore {
        final List<BucketSpec> seen = new ArrayList<>();
        Admission answer = new Admission.Allowed(List.of());
        RuntimeException thrown;

        @Override
        public Admission tryConsume(List<BucketSpec> buckets, int cost) {
            seen.addAll(buckets);
            if (thrown != null) {
                throw thrown;
            }
            return answer;
        }
    }

    static MockHttpServletRequest request(String method, String uri, boolean authenticated) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        if (authenticated) {
            r.setAttribute(CustomerPrincipalResolver.ATTRIBUTE,
                    new CustomerPrincipal(new CustomerId("CUS_unit0001"), new SessionId("SES_unit0001")));
        }
        return r;
    }

    @Test
    void unset_is_disabled_and_partial_or_out_of_range_is_refused() {
        assertThat(props(0, 0, 0, 0).resolveEnabled()).isFalse();
        assertThat(props(10, 1, 5, 0.5).resolveEnabled()).isTrue();
        assertThat(props(1_000_000, 1, 1, 1).resolveEnabled()).isTrue();
        for (CustomerRateLimitProperties bad : List.of(props(10, 1, 0, 0), props(0, 0, 5, 1), props(10, 0, 5, 1),
                props(10, 1, 5, 0), props(-1, 1, 5, 1), props(1_000_001, 1, 5, 1), props(10, 1, 1_000_001, 1))) {
            assertThatThrownBy(bad::resolveEnabled).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void get_and_head_charge_the_read_bucket_everything_else_the_write_bucket_keyed_by_customer() throws Exception {
        Recording store = new Recording();
        CustomerRateLimitFilter f = new CustomerRateLimitFilter(store, props(7, 2, 3, 1), new SimpleMeterRegistry());
        for (String m : new String[]{"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            f.doFilter(request(m, "/v1/customer/orders", true), res, chain);
            assertThat(chain.getRequest()).as(m).isNotNull();
        }
        assertThat(store.seen).extracting(BucketSpec::dimension).containsExactly(BucketDimension.CUSTOMER_READ,
                BucketDimension.CUSTOMER_READ, BucketDimension.CUSTOMER_WRITE, BucketDimension.CUSTOMER_WRITE,
                BucketDimension.CUSTOMER_WRITE, BucketDimension.CUSTOMER_WRITE);
        assertThat(store.seen.get(0).key()).isEqualTo("rl:customer:read:CUS_unit0001");
        assertThat(store.seen.get(0).capacity()).isEqualTo(7);
        assertThat(store.seen.get(0).refillPerSecond()).isEqualTo(2.0);
        assertThat(store.seen.get(2).key()).isEqualTo("rl:customer:write:CUS_unit0001");
        assertThat(store.seen.get(2).capacity()).isEqualTo(3);
        assertThat(store.seen.get(2).refillPerSecond()).isEqualTo(1.0);
    }

    @Test
    void other_surfaces_and_unauthenticated_requests_are_never_charged() throws Exception {
        Recording store = new Recording();
        CustomerRateLimitFilter f = new CustomerRateLimitFilter(store, props(7, 2, 3, 1), new SimpleMeterRegistry());
        for (String uri : new String[]{"/v1/catalog/root", "/api/v1/products", "/v1/customers", "/v1/auth/otp/request"}) {
            MockFilterChain chain = new MockFilterChain();
            f.doFilter(request("GET", uri, true), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(uri).isNotNull();
        }
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(request("GET", "/v1/customer/orders", false), new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(store.seen).isEmpty();
    }

    @Test
    void a_refusal_rounds_retry_after_up_and_never_reaches_the_chain() throws Exception {
        Recording store = new Recording();
        store.answer = new Admission.RateLimited(Duration.ofMillis(1500), List.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CustomerRateLimitFilter f = new CustomerRateLimitFilter(store, props(7, 2, 3, 1), registry);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(request("POST", "/v1/customer/orders", true), res, chain);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("2");
        assertThat(res.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").doesNotContain("CUS_");
        assertThat(registry.get("customer_rate_limit").tag("outcome", "limited").tag("kind", "write").counter().count())
                .isEqualTo(1.0);

        store.answer = new Admission.RateLimited(Duration.ZERO, List.of());
        MockHttpServletResponse zero = new MockHttpServletResponse();
        f.doFilter(request("GET", "/v1/customer/orders", true), zero, new MockFilterChain());
        assertThat(zero.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void a_store_outage_or_throw_fails_closed_with_503() throws Exception {
        Recording store = new Recording();
        store.answer = new Admission.Unavailable("down");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CustomerRateLimitFilter f = new CustomerRateLimitFilter(store, props(7, 2, 3, 1), registry);
        for (int i = 0; i < 2; i++) {
            if (i == 1) {
                store.thrown = new IllegalStateException("redis gone");
            }
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            f.doFilter(request("GET", "/v1/customer/orders", true), res, chain);
            assertThat(chain.getRequest()).isNull();
            assertThat(res.getStatus()).isEqualTo(503);
            assertThat(res.getContentAsString()).contains("\"code\":\"SERVICE_UNAVAILABLE\"").doesNotContain("redis");
        }
        assertThat(registry.get("customer_rate_limit").tag("outcome", "unavailable").counter().count()).isEqualTo(2.0);
    }

    @Test
    void wiring_is_off_when_unset_and_refuses_a_configured_budget_without_a_store() {
        CustomerRateLimitConfig config = new CustomerRateLimitConfig();
        org.springframework.beans.factory.support.StaticListableBeanFactory empty =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();
        assertThat(config.customerRateLimitFilter(props(0, 0, 0, 0), empty.getBeanProvider(RateLimitStore.class),
                empty.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)).isEnabled()).isFalse();
        assertThatThrownBy(() -> config.customerRateLimitFilter(props(7, 2, 3, 1), empty.getBeanProvider(RateLimitStore.class),
                empty.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("REDIS");

        empty.addBean("store", new Recording());
        var reg = config.customerRateLimitFilter(props(7, 2, 3, 1), empty.getBeanProvider(RateLimitStore.class),
                empty.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        assertThat(reg.isEnabled()).isTrue();
        assertThat(reg.getUrlPatterns()).containsExactly("/v1/customer/*");
        assertThat(reg.getOrder()).isGreaterThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 2);
    }
}
