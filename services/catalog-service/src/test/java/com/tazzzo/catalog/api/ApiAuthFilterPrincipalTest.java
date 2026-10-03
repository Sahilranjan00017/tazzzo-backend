package com.tazzzo.catalog.api;

import com.tazzzo.admin.auth.AdminAuthenticatorChain;
import com.tazzzo.admin.auth.AdminPrincipal;
import com.tazzzo.admin.auth.AdminPrincipalResolver;
import com.tazzzo.admin.auth.GoogleIdTokens;
import com.tazzzo.admin.auth.GoogleOidcAuthenticator;
import com.tazzzo.admin.auth.ServiceTokenAuthenticator;
import com.tazzzo.common.audit.ActorType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin filter produces a typed principal, keeps its coarse authorization and counts rejections (no Spring context). */
class ApiAuthFilterPrincipalTest {

    private static final String CMS = "cms-unit-token";
    private static final String READ = "read-unit-token";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-filter");

    private ApiAuthFilter filter(String cms, String read) {
        return new ApiAuthFilter(new AdminAuthenticatorChain(new ServiceTokenAuthenticator(cms, read),
                GoogleOidcAuthenticator.disabled()), new AdminAuthObservability(registry));
    }

    /** Service tokens AND human OIDC enabled (local keys, fixed clock). */
    private ApiAuthFilter humanFilter() {
        return new ApiAuthFilter(new AdminAuthenticatorChain(new ServiceTokenAuthenticator(CMS, READ),
                TOKENS.authenticator(Clock.fixed(NOW, ZoneOffset.UTC))), new AdminAuthObservability(registry));
    }

    private record Outcome(MockHttpServletRequest request, MockHttpServletResponse response, boolean passed) { }

    private Outcome call(ApiAuthFilter f, String method, String token) throws Exception {
        return call(f, method, "/api/v1/products", token);
    }

    private Outcome call(ApiAuthFilter f, String method, String path, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
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

    // ---------- human admins: same principal seam, same Stage B, bounded refusals ----------

    @Test
    void an_allowlisted_human_writer_attaches_a_HUMAN_ADMIN_principal_and_may_write() throws Exception {
        Outcome o = call(humanFilter(), "POST", TOKENS.token(GoogleIdTokens.WRITER, NOW));

        assertThat(o.passed()).isTrue();
        AdminPrincipal p = AdminPrincipalResolver.require(o.request());
        assertThat(p.actorType()).isEqualTo(ActorType.HUMAN_ADMIN);
        assertThat(p.actorId()).isEqualTo("google:" + GoogleIdTokens.WRITER);
        assertThat(AdminActors.require(o.request())).isEqualTo(new com.tazzzo.common.audit.Actor(ActorType.HUMAN_ADMIN,
                "google:" + GoogleIdTokens.WRITER, GoogleIdTokens.CREDENTIAL_ID, "req_unit_0000000000001"));
    }

    @Test
    void an_allowlisted_human_reader_reads_but_may_not_write() throws Exception {
        assertThat(call(humanFilter(), "GET", TOKENS.token(GoogleIdTokens.READER, NOW)).passed()).isTrue();

        Outcome write = call(humanFilter(), "POST", TOKENS.token(GoogleIdTokens.READER, NOW));
        assertThat(write.response().getStatus()).isEqualTo(403);
        assertThat(write.response().getContentAsString()).contains("FORBIDDEN");
        assertThat(AdminPrincipalResolver.current(write.request())).isEmpty();
        assertThat(rejected("forbidden")).isEqualTo(1.0);
    }

    @Test
    void identity_policy_failures_are_401_with_the_uniform_body_and_a_bounded_reason() throws Exception {
        String[][] cases = {
                {TOKENS.token(GoogleIdTokens.WRITER, NOW, c -> c.put("aud", "someone-else")), "invalid_token"},
                {TOKENS.token(GoogleIdTokens.WRITER, NOW.minusSeconds(7200)), "expired_token"},
                {TOKENS.token(GoogleIdTokens.WRITER, NOW, c -> c.remove("hd")), "domain_mismatch"},
                {TOKENS.token(GoogleIdTokens.WRITER, NOW, c -> c.put("email_verified", false)), "email_unverified"},
                {"not-a-token", "invalid_token"},
        };
        String uniform = call(filter(CMS, READ), "GET", null).response().getContentAsString();
        for (String[] c : cases) {
            Outcome o = call(humanFilter(), "GET", c[0]);
            assertThat(o.passed()).as(c[1]).isFalse();
            assertThat(o.response().getStatus()).as(c[1]).isEqualTo(401);
            assertThat(o.response().getContentAsString()).as("no oracle for which check failed").isEqualTo(uniform);
            assertThat(AdminPrincipalResolver.current(o.request())).isEmpty();
        }
        assertThat(rejected("invalid_token")).isEqualTo(2.0);
        assertThat(rejected("expired_token")).isEqualTo(1.0);
        assertThat(rejected("domain_mismatch")).isEqualTo(1.0);
        assertThat(rejected("email_unverified")).isEqualTo(1.0);
    }

    @Test
    void allowlist_refusals_are_403_with_no_principal_and_a_bounded_reason() throws Exception {
        Outcome stranger = call(humanFilter(), "GET", TOKENS.token(GoogleIdTokens.STRANGER, NOW));
        Outcome disabled = call(humanFilter(), "POST", TOKENS.token(GoogleIdTokens.DISABLED, NOW));

        for (Outcome o : new Outcome[]{stranger, disabled}) {
            assertThat(o.passed()).isFalse();
            assertThat(o.response().getStatus()).isEqualTo(403);
            assertThat(o.response().getContentAsString()).contains("FORBIDDEN").contains("admin access not granted")
                    .doesNotContain(GoogleIdTokens.STRANGER).doesNotContain(GoogleIdTokens.DISABLED);
            assertThat(AdminPrincipalResolver.current(o.request())).isEmpty();
        }
        assertThat(rejected("not_allowlisted")).isEqualTo(1.0);
        assertThat(rejected("disabled")).isEqualTo(1.0);
    }

    @Test
    void service_tokens_are_unchanged_when_human_oidc_is_enabled() throws Exception {
        Outcome cms = call(humanFilter(), "POST", CMS);
        assertThat(AdminPrincipalResolver.require(cms.request())).isEqualTo(AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER));
        Outcome read = call(humanFilter(), "GET", READ);
        assertThat(AdminPrincipalResolver.require(read.request())).isEqualTo(AdminPrincipal.sharedToken(AdminPrincipal.READER));
        assertThat(call(humanFilter(), "POST", READ).response().getStatus()).isEqualTo(403);
        assertThat(call(humanFilter(), "GET", null).response().getStatus()).isEqualTo(401);
        assertThat(rejected("unauthenticated")).isEqualTo(1.0);
    }

    @Test
    void an_audit_reader_only_human_reaches_exactly_the_narrow_paths_and_nothing_else() throws Exception {
        String token = TOKENS.token(GoogleIdTokens.AUDITOR, NOW);
        for (String path : new String[]{"/api/v1/admin/audit-events", "/api/v1/admin/me"}) {
            Outcome o = call(humanFilter(), "GET", path, token);
            assertThat(o.passed()).as(path).isTrue();
            assertThat(AdminPrincipalResolver.require(o.request()).canReadAudit()).isTrue();
        }
        for (String path : new String[]{"/api/v1/products", "/api/v1/products/TZP-1", "/api/v1/taxonomy/nodes",
                "/api/v1/admin/audit-events/", "/api/v1/admin/audit-events;x=1", "/api/v1/admin/AUDIT-EVENTS",
                "/api", "/v3/api-docs"}) {
            Outcome o = call(humanFilter(), "GET", path, token);
            assertThat(o.passed()).as(path).isFalse();
            assertThat(o.response().getStatus()).as(path).isEqualTo(403);
            assertThat(o.response().getContentAsString()).contains("FORBIDDEN");
            assertThat(AdminPrincipalResolver.current(o.request())).isEmpty();
        }
        // a dot-segment variant is not even INTERNAL: refused as an unknown surface before credentials are read
        Outcome traversal = call(humanFilter(), "GET", "/api/v1/admin/audit-events/../products", token);
        assertThat(traversal.passed()).isFalse();
        assertThat(traversal.response().getStatus()).isEqualTo(404);
        Outcome write = call(humanFilter(), "POST", "/api/v1/admin/audit-events", token);
        assertThat(write.response().getStatus()).isEqualTo(403);
        assertThat(rejected("forbidden")).isEqualTo(9.0);
    }

    @Test
    void existing_readers_and_writers_keep_every_read() throws Exception {
        for (String path : new String[]{"/api/v1/products", "/api/v1/admin/audit-events", "/api/v1/admin/me"}) {
            assertThat(call(humanFilter(), "GET", path, TOKENS.token(GoogleIdTokens.READER, NOW)).passed()).isTrue();
            assertThat(call(humanFilter(), "GET", path, TOKENS.token(GoogleIdTokens.WRITER, NOW)).passed()).isTrue();
            assertThat(call(humanFilter(), "GET", path, READ).passed()).isTrue();
            assertThat(call(humanFilter(), "GET", path, CMS).passed()).isTrue();
        }
        assertThat(rejected("forbidden")).isZero();
    }

    @Test
    void the_unknown_surface_is_404_before_any_credential_is_judged() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/env");
        req.setAttribute(RequestIdFilter.REQUEST_ID, "req_unit_0000000000002");
        req.addHeader("Authorization", "Bearer " + TOKENS.token(GoogleIdTokens.WRITER, NOW));
        MockHttpServletResponse res = new MockHttpServletResponse();
        humanFilter().doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(404);
        assertThat(registry.find("admin_auth_rejected").counters()).isEmpty();
    }
}
