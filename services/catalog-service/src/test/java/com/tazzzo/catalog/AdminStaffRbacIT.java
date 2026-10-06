package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.auth.GoogleIdTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The staff namespaces over real HTTP with real OIDC tokens (loopback key server) and the shared service tokens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AdminStaffRbacIT.Probes.class})
class AdminStaffRbacIT extends AbstractApiIT {

    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-rbac");
    static final HttpServer KEYS;
    static final String ORDER_OPS = "110000000000000000021";
    static final String SUPPORT = "110000000000000000022";
    static final String BOTH = "110000000000000000023";
    static final String WRITER_ORDER = "110000000000000000024";
    static final String CMS = "cms-test-token";
    static final String READ = "read-test-token";

    static {
        try {
            KEYS = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = TOKENS.publicJwks().toString(true).getBytes(StandardCharsets.UTF_8);
        KEYS.createContext("/certs", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        KEYS.start();
    }

    /** Test-only endpoints standing in for the staff APIs that arrive in later PRs. */
    @RestController
    @RequestMapping("/api/v1/admin")
    static class Probes {
        @RequestMapping({"/orders", "/orders/_probe", "/support/_probe", "/orders2/_probe"})
        Map<String, String> probe() {
            return Map.of("ok", "true");
        }
    }

    @AfterAll
    static void stopKeys() {
        KEYS.stop(0);
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry r) {
        r.add("tazzzo.admin.oidc.issuer", () -> "https://accounts.google.com");
        r.add("tazzzo.admin.oidc.audience", () -> GoogleIdTokens.AUDIENCE);
        r.add("tazzzo.admin.oidc.hosted-domain", () -> GoogleIdTokens.DOMAIN);
        r.add("tazzzo.admin.oidc.credential-label", () -> GoogleIdTokens.LABEL);
        r.add("tazzzo.admin.oidc.jwks-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/certs");
        String[][] users = {
                {GoogleIdTokens.WRITER, GoogleIdTokens.WRITER_EMAIL_LABEL, "cms-writer"},
                {GoogleIdTokens.READER, "reader@tazzzo.test", "reader"},
                {GoogleIdTokens.AUDITOR, "auditor@tazzzo.test", "audit-reader"},
                {ORDER_OPS, "ops@tazzzo.test", "order-ops"},
                {SUPPORT, "support@tazzzo.test", "support-agent"},
                {BOTH, "both@tazzzo.test", "order-ops,support-agent"},
                {WRITER_ORDER, "wo@tazzzo.test", "cms-writer,order-ops"},
        };
        for (int i = 0; i < users.length; i++) {
            String k = "tazzzo.admin.users[" + i + "].";
            String[] u = users[i];
            r.add(k + "provider", () -> "google");
            r.add(k + "subject", () -> u[0]);
            r.add(k + "email", () -> u[1]);
            r.add(k + "roles", () -> u[2]);
            r.add(k + "enabled", () -> "true");
        }
    }

    private static String human(String sub) {
        return TOKENS.token(sub, Instant.now());
    }

    private int call(HttpMethod m, String path, String token) {
        return rest.exchange(URI.create(url(path)), m, new HttpEntity<>(headers(token)), JsonNode.class).getStatusCode().value();
    }

    @Test
    void order_ops_reads_and_writes_orders_and_only_reads_support() {
        String t = human(ORDER_OPS);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orders/_probe", t)).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/orders/_probe", t)).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orders", t)).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/support/_probe", t)).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/support/_probe", t)).isEqualTo(403);
    }

    @Test
    void support_agent_reads_orders_but_cannot_change_them() {
        String t = human(SUPPORT);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orders/_probe", t)).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/orders/_probe", t)).isEqualTo(403);
        assertThat(call(HttpMethod.DELETE, "/api/v1/admin/orders/_probe", t)).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/support/_probe", t)).isEqualTo(200);
    }

    @Test
    void catalogue_roles_and_shared_tokens_never_reach_the_staff_namespaces() {
        for (String t : new String[]{human(GoogleIdTokens.WRITER), human(GoogleIdTokens.READER), human(GoogleIdTokens.AUDITOR), CMS, READ}) {
            assertThat(call(HttpMethod.GET, "/api/v1/admin/orders/_probe", t)).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/api/v1/admin/orders/_probe", t)).isEqualTo(403);
            assertThat(call(HttpMethod.GET, "/api/v1/admin/support/_probe", t)).isEqualTo(403);
            assertThat(call(HttpMethod.GET, "/api/v1/admin/orders", t)).isEqualTo(403);
        }
    }

    @Test
    void a_staff_role_added_to_a_catalogue_writer_grants_the_namespace_but_nothing_else_changes() {
        String t = human(WRITER_ORDER);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/orders/_probe", t)).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/support/_probe", t)).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/admin/support/_probe", t)).as("order-ops only reads support").isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/products/TZP-NOPE", t)).as("still a cms-writer: authorised, so a 404 for the missing product, not a 403").isEqualTo(404);
    }

    @Test
    void staff_roles_confer_nothing_on_the_catalogue_surface_but_me_works() {
        for (String sub : new String[]{ORDER_OPS, SUPPORT, BOTH}) {
            String t = human(sub);
            assertThat(call(HttpMethod.GET, "/api/v1/products", t)).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/api/v1/products", t)).isEqualTo(403);
            assertThat(call(HttpMethod.GET, "/api/v1/taxonomy/nodes", t)).isEqualTo(403);
            assertThat(call(HttpMethod.GET, "/api/v1/admin/audit-events", t)).isEqualTo(403);
            assertThat(call(HttpMethod.GET, "/api/v1/admin/service-areas", t)).isEqualTo(403);
            assertThat(call(HttpMethod.GET, "/api/v1/admin/me", t)).isEqualTo(200);
        }
        ResponseEntity<JsonNode> me = rest.exchange(URI.create(url("/api/v1/admin/me")), HttpMethod.GET, new HttpEntity<>(headers(human(BOTH))), JsonNode.class);
        assertThat(me.getBody().toString()).contains("order-ops").contains("support-agent");
    }

    @Test
    void lookalike_paths_and_decorated_variants_do_not_become_the_namespace() {
        String ops = human(ORDER_OPS);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orders2/_probe", ops)).as("not the orders namespace: legacy rules, no catalogue read").isEqualTo(403);
        String writer = human(GoogleIdTokens.WRITER);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orders2/_probe", writer)).as("legacy rules: a catalogue reader may read it").isEqualTo(200);
        for (String sneaky : new String[]{"/api/v1/admin/orders/%2e%2e/orders/_probe", "/api/v1/admin//orders/_probe", "/api/v1/admin/orders/..;/_probe", "/api/v1/admin/ORDERS/_probe"}) {
            assertThat(call(HttpMethod.GET, sneaky, writer)).as(sneaky).isIn(403, 404);
            assertThat(call(HttpMethod.GET, sneaky, CMS)).as(sneaky).isIn(403, 404);
        }
    }

    @Test
    void authentication_is_still_required_first() {
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orders/_probe", null)).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/orders/_probe", "garbage")).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/support/_probe", TOKENS.token(GoogleIdTokens.STRANGER, Instant.now()))).isEqualTo(403);
    }
}
