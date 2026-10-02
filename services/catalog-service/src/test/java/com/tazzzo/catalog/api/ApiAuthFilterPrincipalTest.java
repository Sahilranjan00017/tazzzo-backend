package com.tazzzo.catalog.api;

import com.tazzzo.admin.auth.AdminPrincipal;
import com.tazzzo.admin.auth.AdminPrincipalResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin filter produces a typed principal, keeps its coarse authorization and counts rejections (no Spring context). */
class ApiAuthFilterPrincipalTest {

    private static final String CMS = "cms-unit-token";
    private static final String READ = "read-unit-token";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ApiAuthFilter filter(String cms, String read) {
        return new ApiAuthFilter(cms, read, new AdminAuthObservability(registry));
    }

    private record Outcome(MockHttpServletRequest request, MockHttpServletResponse response, boolean passed) { }

    private Outcome call(ApiAuthFilter f, String method, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/v1/products");
        req.setAttribute(RequestIdFilter.REQUEST_ID, "req_unit_0000000000001");
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        return new Outcome(req, res, chain.getRequest() != null);
    }

    private double rejected(String reason) {
        var c = registry.find("admin_auth_rejected").tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void the_cms_token_attaches_the_cms_writer_service_principal_and_may_write() throws Exception {
        Outcome o = call(filter(CMS, READ), "POST", CMS);

        assertThat(o.passed()).isTrue();
        assertThat(AdminPrincipalResolver.require(o.request()))
                .isEqualTo(AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER));
        assertThat(o.request().getAttribute("auth_role")).as("the untyped role attribute is gone").isNull();
        assertThat(AdminActors.require(o.request()).requestId()).isEqualTo("req_unit_0000000000001");
    }

    @Test
    void the_read_token_reads_as_the_reader_service_principal_and_may_not_write() throws Exception {
        Outcome read = call(filter(CMS, READ), "GET", READ);
        assertThat(read.passed()).isTrue();
        assertThat(AdminPrincipalResolver.require(read.request())).isEqualTo(AdminPrincipal.sharedToken(AdminPrincipal.READER));

        Outcome write = call(filter(CMS, READ), "POST", READ);
        assertThat(write.passed()).isFalse();
        assertThat(write.response().getStatus()).isEqualTo(403);
        assertThat(write.response().getContentAsString()).contains("FORBIDDEN");
        assertThat(AdminPrincipalResolver.current(write.request())).isEmpty();
        assertThat(rejected("forbidden")).isEqualTo(1.0);
    }

    @Test
    void a_missing_or_unknown_token_is_401_counted_and_attaches_nothing() throws Exception {
        for (String token : new String[]{null, "not-a-token"}) {
            Outcome o = call(filter(CMS, READ), "GET", token);
            assertThat(o.passed()).isFalse();
            assertThat(o.response().getStatus()).isEqualTo(401);
            assertThat(AdminPrincipalResolver.current(o.request())).isEmpty();
        }
        assertThat(rejected("unauthenticated")).isEqualTo(2.0);
        assertThat(rejected("forbidden")).isZero();
    }

    @Test
    void an_unset_token_disables_its_role() throws Exception {
        assertThat(call(filter("", READ), "POST", "").response().getStatus()).isEqualTo(401);
        assertThat(call(filter(CMS, null), "GET", READ).response().getStatus()).isEqualTo(401);
    }

    @Test
    void identical_configured_tokens_keep_the_existing_precedence_cms_writer_wins() throws Exception {
        Outcome o = call(filter("same-token", "same-token"), "POST", "same-token");

        assertThat(o.passed()).as("unchanged behaviour: the cms-writer entry replaces the reader entry").isTrue();
        assertThat(AdminPrincipalResolver.require(o.request()).actorId()).isEqualTo("service:cms-writer");
    }

    @Test
    void the_raw_token_never_reaches_the_principal_the_actor_or_the_error_body() throws Exception {
        Outcome ok = call(filter(CMS, READ), "POST", CMS);
        assertThat(AdminPrincipalResolver.require(ok.request()).toString()).doesNotContain(CMS);
        assertThat(AdminActors.require(ok.request()).toString()).doesNotContain(CMS);

        Outcome denied = call(filter(CMS, READ), "POST", READ);
        assertThat(denied.response().getContentAsString()).doesNotContain(READ);
    }
}
