package com.tazzzo.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.auth.GoogleIdTokens;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.auth.session.SessionEstablishRequestDto;
import com.tazzzo.catalog.AbstractApiIT;
import com.tazzzo.catalog.CatalogApplication;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Support cases end to end: real customer sessions, real staff OIDC tokens, real Mongo. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = CatalogApplication.class)
class SupportCasesIT extends AbstractApiIT {

    static final String CUSTOMER = "/v1/customer/support/cases";
    static final String STAFF = "/api/v1/admin/support/cases";
    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-support");
    static final HttpServer KEYS;
    static final String AGENT = "110000000000000000031";
    static final String OPS = "110000000000000000032";

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

    @AfterAll
    static void stopKeys() {
        KEYS.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(new byte[32]));
        byte[] refresh = new byte[32];
        java.util.Arrays.fill(refresh, (byte) 1);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> Base64.getEncoder().encodeToString(refresh));
        r.add("tazzzo.admin.oidc.issuer", () -> "https://accounts.google.com");
        r.add("tazzzo.admin.oidc.audience", () -> GoogleIdTokens.AUDIENCE);
        r.add("tazzzo.admin.oidc.hosted-domain", () -> GoogleIdTokens.DOMAIN);
        r.add("tazzzo.admin.oidc.credential-label", () -> GoogleIdTokens.LABEL);
        r.add("tazzzo.admin.oidc.jwks-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/certs");
        String[][] users = {{AGENT, "agent@tazzzo.test", "support-agent"}, {OPS, "ops@tazzzo.test", "order-ops"},
                {GoogleIdTokens.WRITER, GoogleIdTokens.WRITER_EMAIL_LABEL, "cms-writer"}};
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

    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired SupportService service;
    @Autowired com.tazzzo.catalog.tx.Tx txBean;

    @BeforeAll
    void clean() {
        schemaBootstrap.bootstrap(db);
        db.getCollection("support_cases").deleteMany(new Document());
    }

    private int seq = 0;

    private String customer() {
        String phone = "+9196" + String.format("%08d", (System.nanoTime() / 13 + ++seq) % 100_000_000);
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, Instant.now(), Instant.now().plusSeconds(300));
        return post("/v1/auth/session", new SessionEstablishRequestDto(grantId), null, JsonNode.class).getBody().get("accessToken").asText();
    }

    private String customerId(String token) {
        return get("/v1/customer/profile", token, JsonNode.class).getBody().get("customerId").asText();
    }

    private ResponseEntity<JsonNode> call(HttpMethod m, String path, String token, Object body) {
        return rest.exchange(URI.create(url(path)), m, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private static Map<String, Object> newCase(String subject) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("category", "DELIVERY");
        m.put("subject", subject);
        m.put("message", "My order did not arrive.\nPlease help.");
        return m;
    }

    private String open(String token) {
        ResponseEntity<JsonNode> r = call(HttpMethod.POST, CUSTOMER, token, newCase("Late delivery"));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(201);
        return r.getBody().get("caseId").asText();
    }

    static String staff(String sub) {
        return TOKENS.token(sub, Instant.now());
    }

    @Test
    void a_customer_opens_reads_replies_and_closes_their_own_case() {
        String t = customer();
        ResponseEntity<JsonNode> created = call(HttpMethod.POST, CUSTOMER, t, newCase("Late delivery"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getCacheControl()).contains("no-store");
        JsonNode c = created.getBody();
        assertThat(c.get("caseId").asText()).matches("SUP_[A-Za-z0-9_-]{20,40}");
        assertThat(c.get("status").asText()).isEqualTo("OPEN");
        assertThat(c.get("messages")).hasSize(1);
        assertThat(c.get("messages").get(0).get("text").asText()).isEqualTo("My order did not arrive.\nPlease help.");
        assertThat(c.toString()).doesNotContain("customerId").doesNotContain("assignedTo").doesNotContain("version");
        String id = c.get("caseId").asText();

        JsonNode replied = call(HttpMethod.POST, CUSTOMER + "/" + id + "/messages", t, Map.of("message", "Any update?")).getBody();
        assertThat(replied.get("messages")).hasSize(2);
        assertThat(call(HttpMethod.GET, CUSTOMER + "/" + id, t, null).getBody().get("messages")).hasSize(2);
        JsonNode list = call(HttpMethod.GET, CUSTOMER, t, null).getBody();
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("messageCount").asInt()).isEqualTo(2);

        assertThat(call(HttpMethod.POST, CUSTOMER + "/" + id + "/close", t, null).getBody().get("status").asText()).isEqualTo("CLOSED");
        assertThat(call(HttpMethod.POST, CUSTOMER + "/" + id + "/close", t, null).getStatusCode().value()).as("idempotent").isEqualTo(200);
        ResponseEntity<JsonNode> late = call(HttpMethod.POST, CUSTOMER + "/" + id + "/messages", t, Map.of("message", "hello?"));
        assertThat(late.getStatusCode().value()).as("a closed case is final").isEqualTo(409);
        assertThat(late.getBody().get("code").asText()).isEqualTo("STATE_CONFLICT");
    }

    @Test
    void ownership_is_the_principal_and_foreign_cases_and_orders_are_not_found() {
        String alice = customer();
        String bob = customer();
        String aliceCase = open(alice);
        assertThat(call(HttpMethod.GET, CUSTOMER + "/" + aliceCase, bob, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, CUSTOMER + "/" + aliceCase + "/messages", bob, Map.of("message", "x")).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, CUSTOMER + "/" + aliceCase + "/close", bob, null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, CUSTOMER, bob, null).getBody().get("items")).isEmpty();

        String aliceId = customerId(alice);
        db.getCollection("orders").insertOne(new Document("_id", "ORD_supportfixture1").append("customerId", aliceId));
        Map<String, Object> aboutOrder = newCase("Missing item");
        aboutOrder.put("orderId", "ORD_supportfixture1");
        assertThat(call(HttpMethod.POST, CUSTOMER, alice, aboutOrder).getBody().get("orderId").asText()).isEqualTo("ORD_supportfixture1");
        assertThat(call(HttpMethod.POST, CUSTOMER, bob, aboutOrder).getStatusCode().value()).as("someone else's order").isEqualTo(404);
        aboutOrder.put("orderId", "not-an-order");
        assertThat(call(HttpMethod.POST, CUSTOMER, alice, aboutOrder).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, CUSTOMER, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, CUSTOMER, "cms-test-token", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void the_request_grammar_is_closed_and_text_is_bounded_plain_text() {
        String t = customer();
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of(), Map.of("category", "DELIVERY", "subject", "s"),
                Map.of("category", "REFUND_ME", "subject", "s", "message", "m"),
                Map.of("category", "DELIVERY", "subject", "s", "message", "m", "customerId", "CUS_x"),
                Map.of("category", "DELIVERY", "subject", "s", "message", "m", "status", "RESOLVED"),
                Map.of("category", "DELIVERY", "subject", "x".repeat(121), "message", "m"),
                Map.of("category", "DELIVERY", "subject", "s", "message", "y".repeat(2001)),
                Map.of("category", "DELIVERY", "subject", "s", "message", "bell\u0007"),
                Map.of("category", "DELIVERY", "subject", "   ", "message", "m"),
                Map.of("category", 7, "subject", "s", "message", "m"))) {
            ResponseEntity<JsonNode> r = call(HttpMethod.POST, CUSTOMER, t, bad);
            assertThat(r.getStatusCode().value()).as(bad.toString()).isEqualTo(400);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(r.getBody().toString()).doesNotContain("Exception");
        }
        assertThat(call(HttpMethod.POST, CUSTOMER, t, "{not json").getStatusCode().value()).isEqualTo(400);
        for (String q : new String[]{"?page_size=0", "?page_size=51", "?cursor=bad", "?status=OPEN", "?customerId=x"}) {
            assertThat(call(HttpMethod.GET, CUSTOMER + q, t, null).getStatusCode().value()).as(q).isEqualTo(400);
        }
        assertThat(call(HttpMethod.GET, CUSTOMER + "/SUP_unknownunknownunknown00", t, null).getStatusCode().value()).isEqualTo(404);
        assertThat(db.getCollection("support_cases").countDocuments(new Document("customerId", customerId(t)))).isZero();
    }

    @Test
    void at_most_five_cases_may_be_open_at_once() {
        String t = customer();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(open(t));
        ResponseEntity<JsonNode> sixth = call(HttpMethod.POST, CUSTOMER, t, newCase("one more"));
        assertThat(sixth.getStatusCode().value()).isEqualTo(409);
        assertThat(sixth.getBody().get("code").asText()).isEqualTo("TOO_MANY_OPEN");
        call(HttpMethod.POST, CUSTOMER + "/" + ids.get(0) + "/close", t, null);
        assertThat(call(HttpMethod.POST, CUSTOMER, t, newCase("now allowed")).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void staff_reply_assign_and_resolve_with_cas_and_audit_and_a_customer_reply_reopens() {
        String t = customer();
        String id = open(t);
        String agent = staff(AGENT);
        JsonNode staffView = call(HttpMethod.GET, STAFF + "/" + id, agent, null).getBody();
        assertThat(staffView.get("customerId").asText()).isEqualTo(customerId(t));
        long v = staffView.get("version").asLong();

        JsonNode assigned = call(HttpMethod.POST, STAFF + "/" + id + "/assign", agent, Map.of("expectedVersion", v)).getBody();
        assertThat(assigned.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(assigned.get("assignedTo").asText()).isEqualTo("google:" + AGENT);
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/assign", agent, Map.of("expectedVersion", v)).getStatusCode().value())
                .as("stale version").isEqualTo(409);

        JsonNode replied = call(HttpMethod.POST, STAFF + "/" + id + "/messages", agent, Map.of("message", "We are on it.")).getBody();
        assertThat(replied.get("messages").get(1).get("author").asText()).isEqualTo("STAFF");
        assertThat(replied.get("messages").get(1).get("staffId").asText()).isEqualTo("google:" + AGENT);
        JsonNode customerSees = call(HttpMethod.GET, CUSTOMER + "/" + id, t, null).getBody();
        assertThat(customerSees.get("messages").get(1).get("author").asText()).isEqualTo("STAFF");
        assertThat(customerSees.toString()).as("the customer never sees staff identities").doesNotContain(AGENT);

        long v2 = replied.get("version").asLong();
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/status", agent, Map.of("expectedVersion", v2, "to", "RESOLVED")).getBody()
                .get("status").asText()).isEqualTo("RESOLVED");
        assertThat(call(HttpMethod.POST, CUSTOMER + "/" + id + "/messages", t, Map.of("message", "Not fixed")).getBody().get("status").asText())
                .as("a customer reply reopens a resolved case").isEqualTo("OPEN");

        List<String> types = db.getCollection("domain_events").find(new Document("aggregate_id", id)).map(d -> d.getString("type")).into(new ArrayList<>());
        assertThat(types).containsExactlyInAnyOrder("SUPPORT_ASSIGNED", "SUPPORT_STAFF_REPLIED", "SUPPORT_STATUS_CHANGED");
        assertThat(db.getCollection("domain_events").find(new Document("aggregate_id", id)).into(new ArrayList<>()))
                .allSatisfy(e -> assertThat(e.get("actor", Document.class).getString("id")).isEqualTo("google:" + AGENT));
        assertThat(db.getCollection("domain_events").countDocuments(new Document("aggregate_id", id))).as("the stale assign left no audit row").isEqualTo(3);
    }

    @Test
    void staff_access_follows_the_policy_and_status_changes_are_validated() {
        String t = customer();
        String id = open(t);
        String ops = staff(OPS);
        assertThat(call(HttpMethod.GET, STAFF + "/" + id, ops, null).getStatusCode().value()).as("order-ops reads").isEqualTo(200);
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/messages", ops, Map.of("message", "hi")).getStatusCode().value()).as("order-ops cannot write").isEqualTo(403);
        for (String token : new String[]{staff(GoogleIdTokens.WRITER), "cms-test-token", "read-test-token"}) {
            assertThat(call(HttpMethod.GET, STAFF, token, null).getStatusCode().value()).isEqualTo(403);
        }
        String agent = staff(AGENT);
        long v = call(HttpMethod.GET, STAFF + "/" + id, agent, null).getBody().get("version").asLong();
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/status", agent, Map.of("expectedVersion", v, "to", "OPEN")).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/status", agent, Map.of("expectedVersion", v, "to", "BOGUS")).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/status", agent, Map.of("to", "RESOLVED")).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/status", agent, Map.of("expectedVersion", v, "to", "CLOSED")).getBody().get("status").asText()).isEqualTo("CLOSED");
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/messages", agent, Map.of("message", "late")).getStatusCode().value()).isEqualTo(409);
        long closedVersion = call(HttpMethod.GET, STAFF + "/" + id, agent, null).getBody().get("version").asLong();
        assertThat(call(HttpMethod.POST, STAFF + "/" + id + "/status", agent, Map.of("expectedVersion", closedVersion, "to", "RESOLVED")).getStatusCode().value())
                .as("a closed case is final for staff too").isEqualTo(409);
        assertThat(call(HttpMethod.GET, STAFF + "/" + id, agent, null).getBody().get("status").asText()).isEqualTo("CLOSED");
        assertThat(call(HttpMethod.GET, STAFF + "/SUP_unknownunknownunknown00", agent, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void the_staff_queue_filters_by_status_and_pages_newest_first() {
        String t = customer();
        String a = open(t);
        String b = open(t);
        String agent = staff(AGENT);
        long v = call(HttpMethod.GET, STAFF + "/" + a, agent, null).getBody().get("version").asLong();
        call(HttpMethod.POST, STAFF + "/" + a + "/status", agent, Map.of("expectedVersion", v, "to", "RESOLVED"));
        JsonNode resolved = call(HttpMethod.GET, STAFF + "?status=RESOLVED", agent, null).getBody();
        assertThat(resolved.get("items")).allSatisfy(i -> assertThat(i.get("status").asText()).isEqualTo("RESOLVED"));
        assertThat(resolved.toString()).contains(a).doesNotContain(b);
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode p = call(HttpMethod.GET, STAFF + "?page_size=1" + (cursor == null ? "" : "&cursor=" + cursor), agent, null).getBody();
            p.get("items").forEach(i -> seen.add(i.get("caseId").asText()));
            cursor = p.hasNonNull("nextCursor") ? p.get("nextCursor").asText() : null;
            assertThat(++pages).isLessThanOrEqualTo(200);
        } while (cursor != null);
        assertThat(seen).doesNotHaveDuplicates().contains(a, b);
        assertThat(seen.indexOf(a)).as("a was updated last, so it is newer").isLessThan(seen.indexOf(b));
        assertThat(call(HttpMethod.GET, STAFF + "?status=NOPE", agent, null).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, STAFF + "?sort=x", agent, null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void erasure_deletes_every_case_of_the_customer_only() {
        String t = customer();
        String other = customer();
        open(t);
        open(t);
        open(other);
        String id = customerId(t);
        Long deleted = txBean.call(s -> service.eraseForCustomer(s, id));
        assertThat(deleted).isEqualTo(2L);
        assertThat(db.getCollection("support_cases").countDocuments(new Document("customerId", id))).isZero();
        assertThat(db.getCollection("support_cases").countDocuments(new Document("customerId", customerId(other)))).isEqualTo(1);
    }
}
