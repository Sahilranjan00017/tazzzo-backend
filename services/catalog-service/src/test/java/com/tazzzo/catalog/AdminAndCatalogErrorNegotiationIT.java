package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.auth.GoogleIdTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP correctness for the nine error boundaries outside the {@code /v1} customer domains: the admin advices (inventory,
 * media, prices, service areas, delivery slots, bulk import, content, staff orders) and the public {@code /catalog/v1}
 * consumer advice. Before, an {@code Accept} they cannot produce turned their typed errors into a 500 with an empty or
 * HTML body, an ERROR line and a stack trace -- on {@code /catalog/v1} for anonymous callers. Now such a request is
 * refused before the handler runs, as JSON; a JSON client's typed errors are unchanged; and nothing is logged as ERROR.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AdminAndCatalogErrorNegotiationIT extends AbstractConsumerIT {

    static final String CMS = "cms-test-token";
    static final String ORDER_OPS = "110000000000000000093";
    static final String J = "application/json";
    static final String XML = "application/xml";
    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-negotiation");
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
        r.add("spring.mongodb.database", () -> "tazzzo_error_negotiation_it");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> CMS);
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString(
                "negotiation-cursor-fixture-32byt".getBytes(StandardCharsets.UTF_8)));
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
        r.add("tazzzo.admin.users[0].subject", () -> ORDER_OPS);
        r.add("tazzzo.admin.users[0].email", () -> "ops@tazzzo.test");
        r.add("tazzzo.admin.users[0].roles", () -> "order-ops");
        r.add("tazzzo.admin.users[0].enabled", () -> "true");
    }

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void schema() {
        schemaBootstrap.bootstrap(db);
    }

    @AfterAll
    void stopKeys() {
        KEYS.stop(0);
    }

    @Test
    void inventory_admin(CapturedOutput log) throws Exception {
        adminTypedErrorThenRefusal("/api/v1/admin/inventory/TZP-NONE/FL-NONE", CMS);
        quiet(log);
    }

    @Test
    void media_admin(CapturedOutput log) throws Exception {
        adminTypedErrorThenRefusal("/api/v1/admin/media/PRODUCT/TZP-NONE", CMS);
        quiet(log);
    }

    @Test
    void price_admin(CapturedOutput log) throws Exception {
        adminTypedErrorThenRefusal("/api/v1/admin/prices/TZP-NONE", CMS);
        quiet(log);
    }

    @Test
    void service_area_admin(CapturedOutput log) throws Exception {
        adminTypedErrorThenRefusal("/api/v1/admin/service-areas/999999", CMS);
        quiet(log);
    }

    @Test
    void delivery_slot_admin(CapturedOutput log) throws Exception {
        adminTypedErrorThenRefusal("/api/v1/admin/delivery-slots/SA-NONE/W-NONE", CMS);
        quiet(log);
    }

    @Test
    void content_admin(CapturedOutput log) throws Exception {
        adminTypedErrorThenRefusal("/api/v1/admin/content/blocks/BLK-NONE", CMS);
        quiet(log);
    }

    @Test
    void staff_orders(CapturedOutput log) throws Exception {
        adminTypedErrorThenRefusal("/api/v1/admin/orders/ORD_none", TOKENS.token(ORDER_OPS, Instant.now()));
        quiet(log);
    }

    /** The import is refused before it is read, so an unacceptable Accept can never start one. */
    @Test
    void bulk_import(CapturedOutput log) throws Exception {
        Res typed = call("POST", "/api/v1/admin/imports/prices", CMS, J, "{\"rows\":\"not-a-list\"}");
        assertThat(typed.status).as(typed.raw).isBetween(400, 499);
        json(typed);
        Res refused = call("POST", "/api/v1/admin/imports/prices", CMS, XML, "{\"rows\":\"not-a-list\"}");
        assertThat(refused.status).as(refused.raw).isEqualTo(406);
        assertThat(refused.body.at("/error/code").asText()).isEqualTo("NOT_ACCEPTABLE");
        json(refused);
        quiet(log);
    }

    /**
     * The anonymous public catalogue: an unacceptable Accept can no longer make it log an ERROR and a stack trace per
     * request. The framework failure keeps the documented ERR-1 public shape ({@code 400 INVALID_REQUEST}).
     */
    @Test
    void public_catalog_cannot_amplify_logs(CapturedOutput log) throws Exception {
        for (String path : new String[]{"/catalog/v1/categories/TZC-NONE/children", "/catalog/v1/categories/TZC-NONE/products",
                "/catalog/v1/products/TZP-NONE"}) {
            Res typed = call("GET", path, null, J, null);
            assertThat(typed.status).as(path + " " + typed.raw).isNotEqualTo(500);
            for (String accept : new String[]{XML, "text/html"}) {
                Res refused = call("GET", path, null, accept, null);
                assertThat(refused.status).as(path + " " + accept + " " + refused.raw).isEqualTo(400);
                assertThat(refused.body.path("code").asText()).isEqualTo("INVALID_REQUEST");
                json(refused);
            }
        }
        quiet(log);
    }

    /** Authorization is unchanged: no credential is a 401, a wrong role a 403, whatever the Accept. */
    @Test
    void authorization_is_unchanged() throws Exception {
        assertThat(call("GET", "/api/v1/admin/prices/TZP-NONE", null, XML, null).status).isEqualTo(401);
        assertThat(call("GET", "/api/v1/admin/prices/TZP-NONE", "bogus", XML, null).status).isEqualTo(401);
        assertThat(call("GET", "/api/v1/admin/orders/ORD_none", "read-test-token", XML, null).status).isEqualTo(403);
    }

    // ---------- helpers ----------

    record Res(int status, String contentType, JsonNode body, String raw) { }

    /** A JSON client still gets the advice's own typed 4xx; an unacceptable Accept is a JSON 406, never a 500. */
    private void adminTypedErrorThenRefusal(String path, String token) throws Exception {
        Res typed = call("GET", path, token, J, null);
        assertThat(typed.status).as(path + " " + typed.raw).isBetween(400, 499);
        json(typed);
        assertThat(typed.body.at("/error/code").asText()).isNotEmpty();
        for (String accept : new String[]{XML, "text/html"}) {
            Res refused = call("GET", path, token, accept, null);
            assertThat(refused.status).as(path + " " + accept + " " + refused.raw).isEqualTo(406);
            assertThat(refused.body.at("/error/code").asText()).isEqualTo("NOT_ACCEPTABLE");
            assertThat(refused.body.at("/error/request_id").asText()).startsWith("req_");
            json(refused);
        }
    }

    Res call(String method, String path, String bearer, String accept, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", J);
        if (accept != null) b.header("Accept", accept);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed;
        try {
            parsed = r.body().isEmpty() ? json.nullNode() : json.readTree(r.body());
        } catch (IOException e) {
            parsed = json.nullNode();
        }
        return new Res(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), parsed, r.body());
    }

    private void json(Res r) {
        assertThat(r.contentType).as(r.raw).startsWith(J);
        assertThat(r.body.isObject()).as(r.raw).isTrue();
        assertThat(r.raw).doesNotContain("Exception").doesNotContain("springframework").doesNotContain("Whitelabel");
    }

    private static void quiet(CapturedOutput log) {
        assertThat(log.getOut()).doesNotContain("\tat org.springframework").doesNotContain("Failure in @ExceptionHandler")
                .doesNotContain("Servlet.service()");
    }
}
