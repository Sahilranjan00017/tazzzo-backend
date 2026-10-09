package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.auth.GoogleIdTokens;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tomcat 11 refuses an undecodable query parameter ({@code %ZZ}, invalid UTF-8) or more than the parameter limit by
 * throwing from {@code getParameterMap()}. {@code MalformedQueryFilter} answers that ONCE for every surface as the
 * surface's own 400 with a fixed message: no parameter name or value, no limit, no stack, no ERROR log. Authentication
 * still runs first; well-formed requests are untouched. Requests go over a raw socket (java.net.URI refuses %ZZ).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class MalformedQueryAllSurfacesIT extends AbstractConsumerIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(
            "malformed-query-access-fixt-32b!".getBytes(StandardCharsets.UTF_8));
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(
            "malformed-query-refresh-fixt-32b".getBytes(StandardCharsets.UTF_8));
    static final String PHONE = "+919876540078";
    static final String SUPPORT_AGENT = "110000000000000000078";
    static final String J = "application/json";
    static final String CMS = "cms-test-token";

    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-malformed-query");
    static final HttpServer KEYS;

    static {
        try {
            KEYS = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] jwks = TOKENS.publicJwks().toString(true).getBytes(StandardCharsets.UTF_8);
        KEYS.createContext("/certs", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, jwks.length);
            ex.getResponseBody().write(jwks);
            ex.close();
        });
        KEYS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_malformed_query_it");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> CMS);
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> ACCESS_KEY);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> REFRESH_KEY);
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString(
                "malformed-query-cursor-fixt-32b!".getBytes(StandardCharsets.UTF_8)));
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.tazzzo.com");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
        r.add("tazzzo.admin.oidc.issuer", () -> "https://accounts.google.com");
        r.add("tazzzo.admin.oidc.audience", () -> GoogleIdTokens.AUDIENCE);
        r.add("tazzzo.admin.oidc.hosted-domain", () -> GoogleIdTokens.DOMAIN);
        r.add("tazzzo.admin.oidc.credential-label", () -> GoogleIdTokens.LABEL);
        r.add("tazzzo.admin.oidc.jwks-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/certs");
        r.add("tazzzo.admin.users[0].provider", () -> "google");
        r.add("tazzzo.admin.users[0].subject", () -> SUPPORT_AGENT);
        r.add("tazzzo.admin.users[0].email", () -> "agent@tazzzo.test");
        r.add("tazzzo.admin.users[0].roles", () -> "support-agent");
        r.add("tazzzo.admin.users[0].enabled", () -> "true");
    }

    @Autowired OtpVerifiedGrantRepository grants;
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    String customer;

    @BeforeAll
    void session() throws Exception {
        schemaBootstrap.bootstrap(db);
        String grantId = "GRANT_" + UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(PHONE), OtpPurpose.LOGIN, Instant.now(),
                Instant.now().plusSeconds(300));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/v1/auth/session")))
                .header("Content-Type", J)
                .POST(HttpRequest.BodyPublishers.ofString("{\"grantId\":\"" + grantId + "\"}")).build();
        HttpResponse<String> established = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(established.statusCode()).as(established.body()).isEqualTo(200);
        customer = json.readTree(established.body()).get("accessToken").asText();
    }

    @AfterAll
    void stopKeys() {
        KEYS.stop(0);
    }

    @Test
    void every_surface_answers_its_own_fixed_400(CapturedOutput log) throws Exception {
        String agent = TOKENS.token(SUPPORT_AGENT, Instant.now());
        List<String[]> internal = List.of(
                new String[]{"/api/v1/products", CMS}, new String[]{"/api/v1/products/by-key", CMS},
                new String[]{"/api/v1/admin/service-areas", CMS}, new String[]{"/api/v1/admin/content/blocks", CMS},
                new String[]{"/api/v1/admin/support/cases", agent});
        List<String[]> pub = List.of(
                new String[]{"/v1/products", null}, new String[]{"/v1/categories", null},
                new String[]{"/v1/search", null}, new String[]{"/v1/serviceability", null},
                new String[]{"/v1/content/home", null}, new String[]{"/v1/content/faqs", null},
                new String[]{"/v1/app-config", null}, new String[]{"/catalog/v1/categories", null},
                new String[]{"/v1/customer/orders", customer}, new String[]{"/v1/customer/support/cases", customer},
                new String[]{"/v1/customer/delivery/slots", customer}, new String[]{"/v1/customer/cart", customer});
        for (String q : List.of("x=%ZZ", "x=%C3%28", "status=%ZZ&pin=%ZZ", manyParams(1500))) {
            for (String[] r : internal) {
                Raw res = raw(r[0] + "?" + q, r[1]);
                assertThat(res.status).as(r[0] + " " + res.body).isEqualTo(400);
                assertThat(res.json().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
                assertThat(res.json().at("/error/message").asText()).isEqualTo("query string is malformed");
                assertThat(res.json().at("/error/request_id").asText()).startsWith("req_");
                clean(res);
            }
            for (String[] r : pub) {
                Raw res = raw(r[0] + "?" + q, r[1]);
                assertThat(res.status).as(r[0] + " " + res.body).isEqualTo(400);
                assertThat(res.json().path("code").asText()).isEqualTo("INVALID_REQUEST");
                assertThat(res.json().path("message").asText()).isEqualTo("invalid request");
                assertThat(res.json().path("request_id").asText()).startsWith("req_");
                clean(res);
            }
        }
        assertThat(log.getOut()).doesNotContain(" ERROR ").doesNotContain("\tat org.apache")
                .doesNotContain("InvalidParameterException").doesNotContain("%ZZ");
    }

    /** Authentication still answers first: an unauthenticated caller learns nothing about the query. */
    @Test
    void authentication_runs_before_the_query_is_inspected() throws Exception {
        assertThat(raw("/api/v1/products?x=%ZZ", null).status).isEqualTo(401);
        assertThat(raw("/api/v1/products?x=%ZZ", "wrong-token").status).isEqualTo(401);
        assertThat(raw("/v1/customer/orders?x=%ZZ", null).status).isEqualTo(401);
        assertThat(raw("/v1/customer/orders?x=%ZZ", "not-a-token").status).isEqualTo(401);
    }

    /** A well-formed query (percent-encoded values, exactly the limit) is not touched by the filter. */
    @Test
    void well_formed_queries_are_untouched() throws Exception {
        for (String t : List.of("/v1/app-config", "/v1/search?q=%C3%A9", "/v1/search?q=a%20b", "/v1/content/faqs")) {
            assertThat(raw(t, null).status).as(t).isNotEqualTo(400).isNotEqualTo(500); // 200, or a deliberate 503 when nothing is published
        }
        assertThat(raw("/v1/customer/orders", customer).status).isEqualTo(200);
    }

    static final String FORM = "application/x-www-form-urlencoded";
    static final List<String> BAD_FORMS = List.of("a=%ZZ", "%C3%28", "%");

    @Autowired org.springframework.context.ApplicationContext context;

    /** No endpoint reads a form body, so Spring's FormContentFilter (which URLDecodes PUT/PATCH/DELETE form bodies) is not installed. */
    @Test
    void spring_form_content_filter_is_not_installed() {
        assertThat(context.getBeanNamesForType(org.springframework.web.filter.FormContentFilter.class)).isEmpty();
        assertThat(context.getBeansOfType(jakarta.servlet.Filter.class).values())
                .noneMatch(f -> f instanceof org.springframework.web.filter.FormContentFilter);
        assertThat(context.getBeansOfType(org.springframework.boot.web.servlet.FilterRegistrationBean.class).values())
                .noneMatch(r -> r.getFilter() instanceof org.springframework.web.filter.FormContentFilter);
    }

    /**
     * A malformed form body on PUT/PATCH/DELETE is never decoded by a filter, so it can no longer escape controller advice as
     * Boot's default error JSON. The natural result is the route's own fixed envelope (404/405/400/415, or the unchanged 200
     * of a route that ignores its body); never a 500, never a timestamp/path/status key, never an echo, never an ERROR log.
     */
    @Test
    void malformed_form_bodies_on_put_patch_delete_get_a_fixed_envelope_on_every_surface(CapturedOutput log) throws Exception {
        // {method, target, token, extra header, expected status, expected code}
        Object[][] matrix = {
            {"PUT", "/v1/products", null, null, 404, "NOT_FOUND"},
            {"PATCH", "/v1/products", null, null, 404, "NOT_FOUND"},
            {"DELETE", "/v1/products", null, null, 404, "NOT_FOUND"},
            {"PUT", "/v1/customer/cart", customer, null, 400, "INVALID_REQUEST"},
            {"PATCH", "/v1/customer/cart", customer, null, 400, "INVALID_REQUEST"},
            {"DELETE", "/v1/customer/cart", customer, null, 200, null},
            {"PUT", "/v1/customer/cart/items/TZV-000001", customer, null, 415, "UNSUPPORTED_MEDIA_TYPE"},
            {"PATCH", "/v1/customer/profile", customer, "If-Match: \"x\"", 415, "INVALID_REQUEST"},
            {"PUT", "/api/v1/products/by-key", CMS, null, 405, "METHOD_NOT_ALLOWED"},
            {"PATCH", "/api/v1/products/by-key", CMS, null, 400, "MISSING_HEADER"},
            {"PATCH", "/api/v1/products/by-key", CMS, "If-Match: 1", 415, "UNSUPPORTED_MEDIA_TYPE"},
            {"DELETE", "/api/v1/products/by-key", CMS, null, 405, "METHOD_NOT_ALLOWED"},
        };
        for (Object[] m : matrix) {
            for (String body : BAD_FORMS) {
                String what = m[0] + " " + m[1] + " " + body;
                Raw res = raw((String) m[0], (String) m[1], (String) m[2], FORM, body, (String) m[3]);
                assertThat(res.status).as(what + " " + res.body).isEqualTo((int) m[4]);
                noDefaultErrorJson(what, res, (String) m[1], (String) m[5]);
            }
        }
        assertThat(log.getOut()).doesNotContain(" ERROR ").doesNotContain("IllegalArgumentException")
                .doesNotContain("URLDecoder").doesNotContain("%ZZ").doesNotContain("%C3%28");
    }

    /** Authentication still answers before anything is decided about the body. */
    @Test
    void malformed_form_bodies_are_still_answered_by_authentication_first(CapturedOutput log) throws Exception {
        for (String m : List.of("PUT", "PATCH", "DELETE")) {
            for (String body : BAD_FORMS) {
                for (String t : new String[]{null, "wrong-token"}) {
                    for (String target : List.of("/api/v1/products/by-key", "/v1/customer/cart", "/v1/customer/cart/items/TZV-000001",
                            "/api/v1/products/PRD_x")) {
                        Raw res = raw(m, target, t, FORM, body, null);
                        assertThat(res.status).as(m + " " + target + " " + res.body).isEqualTo(401);
                        noDefaultErrorJson(m + " " + target, res, target, "UNAUTHENTICATED");
                    }
                }
            }
        }
        assertThat(log.getOut()).doesNotContain(" ERROR ");
    }

    /** Negative controls: ordinary JSON bodies on PUT/PATCH/DELETE are read exactly as before. */
    @Test
    void json_bodies_on_put_patch_delete_are_unaffected() throws Exception {
        Raw found = raw("PATCH", "/api/v1/products/PRD_missing", CMS, J, "{\"title\":\"x\"}", "If-Match: 1");
        assertThat(found.status).as(found.body).isEqualTo(404);
        assertThat(found.json().at("/error/code").asText()).isEqualTo("NOT_FOUND");
        Raw bad = raw("PATCH", "/api/v1/products/PRD_missing", CMS, J, "{bad", "If-Match: 1");
        assertThat(bad.status).as(bad.body).isEqualTo(400);
        assertThat(bad.json().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
        Raw put = raw("PUT", "/v1/customer/cart/items/TZV-000001", customer, J, "{\"quantity\":2}", null);
        assertThat(put.status).as(put.body).isEqualTo(404);
        assertThat(put.json().path("code").asText()).isEqualTo("NOT_FOUND");
        Raw badPut = raw("PUT", "/v1/customer/cart/items/TZV-000001", customer, J, "{bad", null);
        assertThat(badPut.status).as(badPut.body).isEqualTo(400);
        Raw patch = raw("PATCH", "/v1/customer/profile", customer, J, "{}", null);
        assertThat(patch.status).as(patch.body).isEqualTo(428);
        Raw del = raw("DELETE", "/v1/customer/cart/items/TZV-000001", customer, J, "{}", null);
        assertThat(del.status).as(del.body).isEqualTo(404);
        Raw clear = raw("DELETE", "/v1/customer/cart", customer, J, "{}", null);
        assertThat(clear.status).as(clear.body).isEqualTo(200);
        assertThat(clear.json().path("itemCount").asInt(-1)).isZero();
    }

    /** Bulk import stays JSON-only: JSON is read, text/csv and octet-stream are refused as before, and a POST form body is still the Tomcat 400. */
    @Test
    void import_routes_are_unchanged() throws Exception {
        String path = "/api/v1/admin/imports/products";
        Raw json = raw("POST", path, CMS, J, "{\"dryRun\":true,\"rows\":[]}", null);
        assertThat(json.status).as(json.body).isEqualTo(422);
        assertThat(json.json().at("/error/code").asText()).isEqualTo("INVALID_IMPORT");
        for (String type : List.of("text/csv", "application/octet-stream")) {
            Raw res = raw("POST", path, CMS, type, "a,b\n1,2\n", null);
            assertThat(res.status).as(type + " " + res.body).isEqualTo(415);
            assertThat(res.json().at("/error/code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        }
        Raw form = raw("POST", path, CMS, FORM, "a=%ZZ", null);
        assertThat(form.status).as(form.body).isEqualTo(400);
        assertThat(form.json().at("/error/code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    /** Fixed envelope of the surface; none of Boot's default error keys; nothing of the body echoed. */
    private static void noDefaultErrorJson(String what, Raw res, String target, String code) {
        com.fasterxml.jackson.databind.JsonNode j = res.json();
        assertThat(res.body).as(what).doesNotContain("timestamp").doesNotContain("\"path\"").doesNotContain("ZZ")
                .doesNotContain("%C3").doesNotContain("\tat ").doesNotContain("Exception").doesNotContain("URLDecoder");
        if (res.status == 200) {
            return;
        }
        if (target.startsWith("/api/")) {
            assertThat(j.has("status")).as(what).isFalse();
            assertThat(j.path("error").isObject()).as(what + " " + res.body).isTrue();
            assertThat(j.at("/error/code").asText()).as(what).isEqualTo(code);
            assertThat(j.at("/error/request_id").asText()).as(what).startsWith("req_");
        } else {
            assertThat(j.has("status") || j.has("error")).as(what + " " + res.body).isFalse();
            assertThat(j.path("code").asText()).as(what).isEqualTo(code);
            assertThat(j.path("message").asText()).as(what).isNotBlank();
            String id = j.has("request_id") ? j.path("request_id").asText() : j.path("requestId").asText();
            assertThat(id).as(what).startsWith("req_");
        }
    }

    // ---------- helpers ----------

    private static String manyParams(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : "&").append('p').append(i).append("=1");
        }
        return sb.toString();
    }

    /** Fixed body: nothing of the request, the exception or the container is echoed. */
    private static void clean(Raw res) {
        assertThat(res.body).doesNotContain("Parameter").doesNotContain("parameter").doesNotContain("ZZ")
                .doesNotContain("p1499").doesNotContain("Exception")
                .doesNotContain("\tat ").doesNotContain("tomcat").doesNotContain("x=");
    }

    record Raw(int status, String body) {
        JsonNode json() {
            try {
                return new ObjectMapper().readTree(body);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Raw HTTP/1.1 GET so the malformed query reaches the server byte-for-byte. */
    Raw raw(String target, String token) throws IOException {
        return raw("GET", target, token, null, null, null);
    }

    /** Raw HTTP/1.1 request so the malformed query or body reaches the server byte-for-byte. */
    Raw raw(String method, String target, String token, String contentType, String body, String extra) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(15000);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.ISO_8859_1);
            String request = method + " " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + (token == null ? "" : "Authorization: Bearer " + token + "\r\n")
                    + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n")
                    + (body == null ? "" : "Content-Length: " + payload.length + "\r\n")
                    + (extra != null ? extra + "\r\n"
                    : target.startsWith("/v1/customer/cart") && !"GET".equals(method) ? "If-Match: \"cart-0\"\r\n" : "")
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            String all = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(all.substring(9, 12));
            String raw = all.substring(all.indexOf("\r\n\r\n") + 4);
            int open = raw.indexOf('{');
            int close = raw.lastIndexOf('}');
            return new Raw(status, open >= 0 && close > open ? raw.substring(open, close + 1) : raw);
        }
    }
}
