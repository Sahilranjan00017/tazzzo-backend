package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.auth.GoogleIdTokens;
import com.tazzzo.auth.CustomerAccessTokenCodec;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.SessionId;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.ActorType;
import com.tazzzo.common.audit.TestActors;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Human admins over real HTTP and a real MongoDB, through the PRODUCTION wiring: {@code tazzzo.admin.*} properties,
 * {@code AdminAuthConfig}, Nimbus' remote key source fetching a locally generated key set from a loopback server (never
 * Google), {@code ApiAuthFilter}, {@code AdminActors}, the admin service and its transactional event ledger.
 */
class HumanAdminOidcIT extends AbstractApiIT {

    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-it");
    static final HttpServer KEYS;
    static final String CUSTOMER_KEY_B64 = Base64.getEncoder().encodeToString(
            "human-admin-it-customer-key-32by".getBytes(StandardCharsets.UTF_8));

    static {
        try {
            KEYS = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = TOKENS.publicJwks().toString(true).getBytes(StandardCharsets.UTF_8);
        KEYS.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        KEYS.start();
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry r) {
        r.add("tazzzo.admin.oidc.issuer", () -> "https://accounts.google.com");
        r.add("tazzzo.admin.oidc.audience", () -> GoogleIdTokens.AUDIENCE);
        r.add("tazzzo.admin.oidc.hosted-domain", () -> GoogleIdTokens.DOMAIN);
        r.add("tazzzo.admin.oidc.credential-label", () -> GoogleIdTokens.LABEL);
        r.add("tazzzo.admin.oidc.jwks-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/certs");
        String[][] users = {
                {GoogleIdTokens.WRITER, GoogleIdTokens.WRITER_EMAIL_LABEL, "cms-writer", "true"},
                {GoogleIdTokens.READER, "reader@tazzzo.test", "reader", "true"},
                {GoogleIdTokens.DISABLED, "gone@tazzzo.test", "cms-writer", "false"},
        };
        for (int i = 0; i < users.length; i++) {
            String k = "tazzzo.admin.users[" + i + "].";
            String[] u = users[i];
            r.add(k + "provider", () -> "google");
            r.add(k + "subject", () -> u[0]);
            r.add(k + "email", () -> u[1]);
            r.add(k + "roles", () -> u[2]);
            r.add(k + "enabled", () -> u[3]);
        }
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> CUSTOMER_KEY_B64);
    }

    @AfterAll
    void stopKeys() {
        KEYS.stop(0);
    }

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired MeterRegistry registry;
    @Autowired CustomerAccessTokenCodec customerTokens;

    static final String BASMATI = "TZV-000001";

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
    }

    private static String human(String sub) {
        return TOKENS.token(sub, Instant.now());
    }

    private Map<String, Object> product(String id, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", key);
        m.put("brandCode", "BR-HUM");
        m.put("title", "Human " + id);
        m.put("verticalId", BASMATI);
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        return m;
    }

    private List<Document> productEvents(String productId) {
        return db.getCollection("product_events").find(Filters.eq("product_id", productId)).into(new ArrayList<>());
    }

    private long productCount(String productId) {
        return db.getCollection("products").countDocuments(Filters.eq("_id", productId));
    }

    private double rejected(String reason) {
        var c = registry.find("admin_auth_rejected").tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    // ---------- the HUMAN_ADMIN audit end-to-end ----------

    @Test
    void Q_an_allowlisted_human_writer_mutation_is_audited_as_HUMAN_ADMIN_with_the_response_request_id() {
        String token = TOKENS.token(GoogleIdTokens.WRITER, Instant.now(),
                c -> c.put("email", "renamed.writer@" + GoogleIdTokens.DOMAIN));
        ResponseEntity<JsonNode> created = post("/api/v1/products", product("TZP-HUM-1", "hum|1"), token, JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = created.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).startsWith("req_");
        Actor expected = new Actor(ActorType.HUMAN_ADMIN, "google:" + GoogleIdTokens.WRITER, GoogleIdTokens.CREDENTIAL_ID,
                requestId);
        List<Document> events = productEvents("TZP-HUM-1");
        assertThat(events).isNotEmpty();
        for (Document e : events) {
            assertThat(ActorDocuments.fromEvent(e)).as(e.toJson()).contains(expected);
            Document actor = (Document) e.get("actor");
            assertThat(actor.keySet()).containsExactlyInAnyOrder("type", "id", "credential_id", "request_id");
            assertThat(actor.getString("type")).isEqualTo("HUMAN_ADMIN");
            assertThat(actor.getString("id")).isEqualTo("google:" + GoogleIdTokens.WRITER);
            assertThat(actor.getString("credential_id")).isEqualTo("oidc:google:cms-test");
            assertThat(actor.getString("request_id")).isEqualTo(requestId);
        }
        assertNoIdentityMetadataOrTokenPersisted("TZP-HUM-1", token);
    }

    /** Only {@code google:<sub>} is persisted: no email (token or label), name, picture, hd, claims or token material. */
    private void assertNoIdentityMetadataOrTokenPersisted(String productId, String token) {
        List<String> stored = new ArrayList<>();
        productEvents(productId).forEach(d -> stored.add(d.toJson()));
        stored.add(db.getCollection("products").find(Filters.eq("_id", productId)).first().toJson());
        String[] segments = token.split("\\.");
        for (String json : stored) {
            assertThat(json)
                    .doesNotContain("@")
                    .doesNotContain(GoogleIdTokens.WRITER_EMAIL_LABEL)
                    .doesNotContain("Test Admin")
                    .doesNotContain("example.invalid")
                    .doesNotContain("email")
                    .doesNotContain("picture")
                    .doesNotContain("\"hd\"")
                    .doesNotContain(GoogleIdTokens.AUDIENCE)
                    .doesNotContain(segments[0])
                    .doesNotContain(segments[1])
                    .doesNotContain(segments[2]);
        }
    }

    @Test
    void a_human_taxonomy_mutation_writes_the_human_actor_on_its_node_event() {
        assertThat(post("/api/v1/taxonomy/releases", Map.of("releaseId", "6.0.0", "basedOn", "0.9.0"),
                human(GoogleIdTokens.WRITER), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        int version = db.getCollection("taxonomy_nodes").find(Filters.eq("_id", BASMATI)).first().getInteger("version");

        ResponseEntity<JsonNode> renamed = post("/api/v1/taxonomy/nodes/" + BASMATI + "/rename",
                Map.of("name", "Basmati Rice (human)", "expectedVersion", version), human(GoogleIdTokens.WRITER),
                JsonNode.class);

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Document nodeEvent = db.getCollection("node_events").find(Filters.and(Filters.eq("node_id", BASMATI),
                Filters.eq("event", "renamed"), Filters.eq("actor.type", "HUMAN_ADMIN"))).first();
        assertThat(nodeEvent).isNotNull();
        assertThat(ActorDocuments.fromEvent(nodeEvent)).contains(new Actor(ActorType.HUMAN_ADMIN,
                "google:" + GoogleIdTokens.WRITER, GoogleIdTokens.CREDENTIAL_ID,
                renamed.getHeaders().getFirst("X-Request-Id")));
    }

    // ---------- M, N, O, P ----------

    @Test
    void M_a_valid_in_domain_human_not_on_the_allowlist_is_403_counted_and_writes_nothing() {
        double before = rejected("not_allowlisted");
        ResponseEntity<JsonNode> denied = post("/api/v1/products", product("TZP-HUM-M", "hum|m"),
                human(GoogleIdTokens.STRANGER), JsonNode.class);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(rejected("not_allowlisted")).isEqualTo(before + 1);
        assertThat(productCount("TZP-HUM-M")).isZero();
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, human(GoogleIdTokens.STRANGER), JsonNode.class)
                .getStatusCode()).as("not even reads").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void N_an_allowlisted_but_disabled_human_is_403_counted_and_writes_nothing() {
        double before = rejected("disabled");
        ResponseEntity<JsonNode> denied = post("/api/v1/products", product("TZP-HUM-N", "hum|n"),
                human(GoogleIdTokens.DISABLED), JsonNode.class);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected("disabled")).isEqualTo(before + 1);
        assertThat(productCount("TZP-HUM-N")).isZero();
        assertThat(productEvents("TZP-HUM-N")).isEmpty();
    }

    @Test
    void O_an_allowlisted_human_reader_reads() {
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, human(GoogleIdTokens.READER), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void P_an_allowlisted_human_reader_mutation_is_403_even_when_the_token_claims_a_writer_role() {
        double before = rejected("forbidden");
        String claimsWriter = TOKENS.token(GoogleIdTokens.READER, Instant.now(), c -> c.put("roles", List.of("cms-writer")));
        ResponseEntity<JsonNode> denied = post("/api/v1/products", product("TZP-HUM-P", "hum|p"), claimsWriter,
                JsonNode.class);

        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected("forbidden")).isEqualTo(before + 1);
        assertThat(productCount("TZP-HUM-P")).isZero();
    }

    @Test
    void identity_policy_failures_over_http_are_401_and_write_nothing() {
        Instant now = Instant.now();
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("expired_token", TOKENS.token(GoogleIdTokens.WRITER, now.minusSeconds(7200)));
        cases.put("domain_mismatch", TOKENS.token(GoogleIdTokens.WRITER, now, c -> {
            c.remove("hd");
            c.put("email", "writer@gmail.com");
        }));
        cases.put("email_unverified", TOKENS.token(GoogleIdTokens.WRITER, now, c -> c.put("email_verified", false)));
        cases.put("invalid_token", TOKENS.token(GoogleIdTokens.WRITER, now, c -> c.put("aud", "customer-app-client")));
        int i = 0;
        for (Map.Entry<String, String> c : cases.entrySet()) {
            double before = rejected(c.getKey());
            String id = "TZP-HUM-X" + (i++);
            ResponseEntity<JsonNode> denied = post("/api/v1/products", product(id, "hum|x" + i), c.getValue(), JsonNode.class);
            assertThat(denied.getStatusCode()).as(c.getKey()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(denied.getBody().path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(rejected(c.getKey())).as(c.getKey()).isEqualTo(before + 1);
            assertThat(productCount(id)).isZero();
        }
    }

    // ---------- T: service accounts unchanged, alongside humans ----------

    @Test
    void T_the_shared_service_tokens_keep_working_with_human_oidc_enabled() {
        ResponseEntity<JsonNode> created = post("/api/v1/products", product("TZP-HUM-T", "hum|t"), CMS_TOKEN, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (Document e : productEvents("TZP-HUM-T")) {
            assertThat(ActorDocuments.fromEvent(e)).contains(new Actor(ActorType.SERVICE_ACCOUNT, "service:cms-writer",
                    "shared-token:cms-writer", created.getHeaders().getFirst("X-Request-Id")));
        }
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, READ_TOKEN, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/products", product("TZP-HUM-T2", "hum|t2"), READ_TOKEN, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/products", product("TZP-HUM-T3", "hum|t3"), "not-a-token", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, null, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(productCount("TZP-HUM-T2") + productCount("TZP-HUM-T3")).isZero();
    }

    // ---------- U, V: customer and admin trust domains stay separate ----------

    @Test
    void U_a_customer_access_token_never_authenticates_the_internal_admin_surface() {
        String customer = customerTokens.issue(new CustomerPrincipal(new CustomerId("CUS_humanadminit"),
                new SessionId("SES_humanadminit")), Duration.ofMinutes(5));

        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, customer, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/v1/products", product("TZP-HUM-U", "hum|u"), customer, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(productCount("TZP-HUM-U")).isZero();
    }

    @Test
    void V_a_valid_human_admin_token_never_authenticates_the_customer_surface() {
        String writer = human(GoogleIdTokens.WRITER);
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, writer, JsonNode.class).getStatusCode())
                .as("the same token IS a valid admin credential").isEqualTo(HttpStatus.OK);

        for (String path : new String[]{"/v1/customer/cart", "/v1/customer/addresses"}) {
            ResponseEntity<JsonNode> res = rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(writer)),
                    JsonNode.class);
            assertThat(res.getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void the_unknown_surface_stays_404_for_a_valid_human_token() {
        HttpHeaders h = headers(human(GoogleIdTokens.WRITER));
        assertThat(rest.exchange(url("/actuator/env"), HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
