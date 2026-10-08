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
 * Error-handling hardening, over real HTTP: a malformed body is a 400, an unsupported {@code Content-Type} a 415 and an
 * unacceptable {@code Accept} a 406 at EVERY controller-scoped error boundary -- never the 500 (logged as ERROR) their
 * {@code Exception} catch-alls used to produce. Each body keeps its domain's documented shape and code vocabulary,
 * carries the request id, and leaks nothing; an unacceptable {@code Accept} is refused (406) before the handler runs; authentication
 * still runs first; and no expected client error writes an ERROR line or a stack trace.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class MalformedRequestErrorMappingIT extends AbstractConsumerIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(
            "error-mapping-access-fixture-32b".getBytes(StandardCharsets.UTF_8));
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(
            "error-mapping-refresh-fixture-32".getBytes(StandardCharsets.UTF_8));
    static final String PHONE = "+919876540077";
    static final String SUPPORT_AGENT = "110000000000000000077";
    static final String J = "application/json";
    static final String XML = "application/xml";

    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-error-mapping");
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
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_error_mapping_it");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> ACCESS_KEY);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> REFRESH_KEY);
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString(
                "error-mapping-cursor-fixture-32b".getBytes(StandardCharsets.UTF_8)));
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
        Res established = call("POST", "/v1/auth/session", J, J, "{\"grantId\":\"" + grantId + "\"}", null);
        assertThat(established.status).as(established.raw).isEqualTo(200);
        customer = established.body.get("accessToken").asText();
    }

    @AfterAll
    void stopKeys() {
        KEYS.stop(0);
    }

    // ---------- the 13 controller-scoped error boundaries ----------

    @Test
    void otp(CapturedOutput log) throws Exception {
        for (String p : List.of("/v1/auth/otp/request", "/v1/auth/otp/verify")) {
            clientError(call("POST", p, J, null, "{", null), 400, "OTP_INVALID_REQUEST");
            clientError(call("POST", p, "text/plain", null, "x", null), 415, "OTP_INVALID_REQUEST");
            clientError(call("POST", p, "multipart/form-data; boundary=zz", null, "--zz--", null), 415, "OTP_INVALID_REQUEST");
            clientError(call("POST", p, null, null, "{}", null), 415, "OTP_INVALID_REQUEST");
            // an Accept the route cannot produce is refused first (406, before the body is read), in the documented shape
            clientError(call("POST", p, J, XML, "{}", null), 406, "OTP_INVALID_REQUEST");
        }
        quiet(log);
    }

    @Test
    void session(CapturedOutput log) throws Exception {
        for (String p : List.of("/v1/auth/session", "/v1/auth/refresh")) {
            clientError(call("POST", p, J, null, "{", null), 400, "INVALID_REQUEST");
            clientError(call("POST", p, "text/plain", null, "x", null), 415, "INVALID_REQUEST");
            clientError(call("POST", p, XML, null, "<a/>", null), 415, "INVALID_REQUEST");
            clientError(call("POST", p, J, XML, "{}", null), 406, "INVALID_REQUEST");
        }
        quiet(log);
    }

    @Test
    void customer_profile(CapturedOutput log) throws Exception {
        clientError(call("PATCH", "/v1/customer/profile", J, null, "{", customer), 400, "INVALID_REQUEST");
        clientError(call("PATCH", "/v1/customer/profile", "text/plain", null, "x", customer), 415, "INVALID_REQUEST");
        clientError(call("PATCH", "/v1/customer/profile", J, XML, "{", customer), 406, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/profile", customer, "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void address(CapturedOutput log) throws Exception {
        clientError(call("POST", "/v1/customer/addresses", J, null, "{", customer), 400, "INVALID_REQUEST");
        clientError(call("POST", "/v1/customer/addresses", "text/plain", null, "x", customer), 415, "INVALID_REQUEST");
        clientError(call("POST", "/v1/customer/addresses", "text/plain", XML, "x", customer), 406, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/addresses", customer, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/addresses/ADR_doesnotexist0000000000", customer, "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void account_deletion(CapturedOutput log) throws Exception {
        clientError(call("POST", "/v1/customer/account/deletion", "text/plain", null, "x", customer), 415, "INVALID_REQUEST");
        clientError(call("POST", "/v1/customer/account/deletion", J, null, "{", customer), 400, "INVALID_REQUEST");
        clientError(call("POST", "/v1/customer/account/deletion", J, XML, "{\"confirm\":\"NO\"}", customer), 406,
                "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void customer_support(CapturedOutput log) throws Exception {
        clientError(call("POST", "/v1/customer/support/cases", "text/plain", null, "x", customer), 415, "INVALID_REQUEST");
        clientError(call("POST", "/v1/customer/support/cases", "text/plain", XML, "x", customer), 406, "INVALID_REQUEST");
        clientError(call("POST", "/v1/customer/support/cases", J, null, "{", customer), 400, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/support/cases", customer, "INVALID_REQUEST");
        quiet(log);
    }

    /** StaffSupport has no catch-all: its framework failures reach the global advice, whose statuses are kept. */
    @Test
    void staff_support(CapturedOutput log) throws Exception {
        String agent = TOKENS.token(SUPPORT_AGENT, Instant.now());
        Res unsupported = call("POST", "/api/v1/admin/support/cases/SUP_none/messages", "text/plain", null, "x", agent);
        assertThat(unsupported.status).as(unsupported.raw).isEqualTo(415);
        assertThat(unsupported.body.at("/error/code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        json(unsupported);
        Res missing = call("GET", "/api/v1/admin/support/cases/SUP_none", null, J, null, agent);
        Res missingXml = call("GET", "/api/v1/admin/support/cases/SUP_none", null, XML, null, agent);
        assertThat(missing.status).isBetween(400, 499);
        assertThat(missingXml.status).as("refused before the handler runs").isEqualTo(406);
        assertThat(missingXml.body.at("/error/code").asText()).isEqualTo("NOT_ACCEPTABLE");
        json(missingXml);
        acceptInvariant("GET", "/api/v1/admin/support/cases", agent, "NOT_ACCEPTABLE");
        quiet(log);
    }

    @Test
    void delivery_slots(CapturedOutput log) throws Exception {
        acceptInvariant("GET", "/v1/customer/delivery/slots?pin=560047", customer, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/delivery/slots", customer, "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void cart(CapturedOutput log) throws Exception {
        clientError(call("PUT", "/v1/customer/cart/items/TZP-NONE", "text/plain", null, "x", customer), 415,
                "UNSUPPORTED_MEDIA_TYPE");
        clientError(call("PUT", "/v1/customer/cart/items/TZP-NONE", J, null, "{", customer), 400, "INVALID_REQUEST");
        clientError(call("PUT", "/v1/customer/cart/items/TZP-NONE", J, XML, "{", customer), 406, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/cart", customer, "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void checkout(CapturedOutput log) throws Exception {
        clientError(call("POST", "/v1/customer/checkout/quote", "text/plain", null, "x", customer), 415,
                "UNSUPPORTED_MEDIA_TYPE");
        clientError(call("POST", "/v1/customer/checkout/quote", J, XML, "{", customer), 406, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/checkout/quotes/QTE_none", customer, "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void order(CapturedOutput log) throws Exception {
        clientError(call("POST", "/v1/customer/orders", "text/plain", null, "x", customer), 415, "UNSUPPORTED_MEDIA_TYPE");
        clientError(call("POST", "/v1/customer/orders", J, XML, "{", customer), 406, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/orders", customer, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/customer/orders/ORD_none", customer, "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void commerce_read(CapturedOutput log) throws Exception {
        acceptInvariant("GET", "/v1/categories", null, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/search?q=milk", null, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/products/TZP-DOES-NOT-EXIST", null, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/serviceability?pin=560001", null, "INVALID_REQUEST");
        quiet(log);
    }

    @Test
    void public_content(CapturedOutput log) throws Exception {
        acceptInvariant("GET", "/v1/content/home", null, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/app-config", null, "INVALID_REQUEST");
        acceptInvariant("GET", "/v1/content/faqs", null, "INVALID_REQUEST");
        quiet(log);
    }

    // ---------- boundaries that must NOT move ----------

    /** Authentication still runs before any body is read: a malformed request without a token is a 401, not a 4xx. */
    @Test
    void authentication_still_runs_first() throws Exception {
        for (String[] r : new String[][]{{"PATCH", "/v1/customer/profile"}, {"POST", "/v1/customer/addresses"},
                {"POST", "/v1/customer/account/deletion"}, {"POST", "/v1/customer/support/cases"},
                {"POST", "/v1/customer/checkout/quote"}, {"POST", "/v1/customer/orders"}}) {
            assertThat(call(r[0], r[1], "text/plain", XML, "x", null).status).as(r[1]).isEqualTo(401);
            assertThat(call(r[0], r[1], J, null, "{", "not-a-token").status).as(r[1]).isEqualTo(401);
        }
        assertThat(call("POST", "/api/v1/admin/support/cases/SUP_none/messages", "text/plain", null, "x", null).status)
                .isEqualTo(401);
    }

    /** The client's correlation id still round-trips on a mapped client error, beside the server-minted request id. */
    @Test
    void correlation_id_is_preserved_on_client_errors() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/v1/auth/otp/request")))
                .header("Content-Type", "text/plain").header("X-Correlation-Id", "corr-err-001")
                .POST(HttpRequest.BodyPublishers.ofString("x")).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(415);
        assertThat(res.headers().firstValue("X-Correlation-Id")).contains("corr-err-001");
        assertThat(json.readTree(res.body()).get("requestId").asText())
                .isEqualTo(res.headers().firstValue("X-Request-Id").orElseThrow());
    }

    // ---------- helpers ----------

    record Res(int status, String contentType, String requestIdHeader, JsonNode body, String raw) { }

    Res call(String method, String path, String contentType, String accept, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        if (accept != null) b.header("Accept", accept);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = r.body().isEmpty() ? json.nullNode() : parseOrNull(r.body());
        return new Res(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                r.headers().firstValue("X-Request-Id").orElse(null), parsed, r.body());
    }

    private JsonNode parseOrNull(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return json.nullNode();
        }
    }

    /** The documented flat {@code {code, message, requestId}} shape, the right status, nothing leaked. */
    private void clientError(Res r, int status, String code) {
        assertThat(r.status).as(r.raw).isEqualTo(status);
        json(r);
        assertThat(r.body.get("code").asText()).as(r.raw).isEqualTo(code);
        assertThat(r.body.get("message").asText()).matches("[a-z \"{}:A-Z]+");
        assertThat(r.body.get("requestId").asText()).as("request id correlates body and header").isEqualTo(r.requestIdHeader);
    }

    /**
     * An {@code Accept} the route cannot satisfy is refused with 406 BEFORE the handler runs, whatever the handler would
     * have answered (so a 406 can never follow a side effect), as a JSON error body -- never a 500 or an empty body.
     */
    private void acceptInvariant(String method, String path, String bearer, String codeOn406) throws Exception {
        Res asJson = call(method, path, null, J, null, bearer);
        Res asXml = call(method, path, null, XML, null, bearer);
        // a deliberate 503 (e.g. no published catalogue release here) is a documented outcome; a 500 never is
        assertThat(asJson.status).as(path + " " + asJson.raw).isNotEqualTo(500);
        assertThat(asJson.status).as("a JSON client is never refused: " + asJson.raw).isNotEqualTo(406);
        json(asXml);
        assertThat(asXml.status).as(path + " " + asXml.raw).isEqualTo(406);
        assertThat(code(asXml)).isEqualTo(codeOn406);
    }

    private static String code(Res r) {
        return r.body.has("error") ? r.body.at("/error/code").asText() : r.body.path("code").asText();
    }

    private void json(Res r) {
        assertThat(r.contentType).as(r.raw).startsWith("application/json");
        assertThat(r.body.isObject()).as(r.raw).isTrue();
        assertThat(r.raw).doesNotContain("Exception").doesNotContain("springframework").doesNotContain("jackson")
                .doesNotContain("JSON parse").doesNotContain("Unexpected character")
                .doesNotContain(PHONE).doesNotContain("\tat ");
        if (customer != null) {
            assertThat(r.raw).doesNotContain(customer);
        }
    }

    /** No expected client error writes an ERROR-level catch-all line or a framework stack trace. */
    private static void quiet(CapturedOutput log) {
        assertThat(log.getOut()).doesNotContain("_internal type=").doesNotContain("\tat org.springframework")
                .doesNotContain("Failure in @ExceptionHandler");
    }
}
