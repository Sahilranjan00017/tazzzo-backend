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
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(15000);
            String request = "GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            String all = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(all.substring(9, 12));
            String body = all.substring(all.indexOf("\r\n\r\n") + 4);
            int open = body.indexOf('{');
            int close = body.lastIndexOf('}');
            return new Raw(status, open >= 0 && close > open ? body.substring(open, close + 1) : body);
        }
    }
}
