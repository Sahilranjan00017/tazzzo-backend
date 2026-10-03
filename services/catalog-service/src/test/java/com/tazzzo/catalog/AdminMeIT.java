package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.auth.GoogleIdTokens;
import com.tazzzo.catalog.api.SurfaceClassifier;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/admin/me} over real HTTP with the production auth wiring: humans (Google OIDC against a loopback key
 * server, never Google) and the shared service tokens. A pure read of the attached principal plus the allowlist label.
 */
class AdminMeIT extends AbstractApiIT {

    static final String ME = "/api/v1/admin/me";
    static final String BOTH = "110000000000000000004";
    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-me");
    static final HttpServer KEYS;

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
                {BOTH, "both@tazzzo.test", "reader,cms-writer", "true"},
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
    }

    @AfterAll
    void stopKeys() {
        KEYS.stop(0);
    }

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
    }

    private static String human(String sub) {
        return TOKENS.token(sub, Instant.now());
    }

    private ResponseEntity<JsonNode> me(String token) {
        return get(ME, token, JsonNode.class);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> roles(JsonNode node) {
        List<String> roles = new ArrayList<>();
        node.path("roles").forEach(r -> roles.add(r.asText()));
        return roles;
    }

    // ---------- humans ----------

    @Test
    void a_human_reader_gets_its_identity_label_and_roles() {
        ResponseEntity<JsonNode> res = me(human(GoogleIdTokens.READER));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = res.getBody();
        assertThat(fieldNames(body)).containsExactly("actorType", "actorId", "email", "roles");
        assertThat(body.path("actorType").asText()).isEqualTo("HUMAN_ADMIN");
        assertThat(body.path("actorId").asText()).isEqualTo("google:" + GoogleIdTokens.READER);
        assertThat(body.path("email").asText()).isEqualTo("reader@tazzzo.test");
        assertThat(roles(body)).containsExactly("reader");
    }

    @Test
    void a_human_writer_gets_its_identity_label_and_roles() {
        JsonNode body = me(human(GoogleIdTokens.WRITER)).getBody();

        assertThat(body.path("actorType").asText()).isEqualTo("HUMAN_ADMIN");
        assertThat(body.path("actorId").asText()).isEqualTo("google:" + GoogleIdTokens.WRITER);
        assertThat(body.path("email").asText()).isEqualTo(GoogleIdTokens.WRITER_EMAIL_LABEL);
        assertThat(roles(body)).containsExactly("cms-writer");
    }

    @Test
    void roles_are_sorted_deterministically() {
        for (int i = 0; i < 5; i++) {
            assertThat(roles(me(human(BOTH)).getBody())).containsExactly("cms-writer", "reader");
        }
    }

    @Test
    void the_email_is_the_configured_label_never_the_token_email_and_the_identity_is_the_sub() {
        String token = TOKENS.token(GoogleIdTokens.WRITER, Instant.now(), c -> c.put("email", "different.person@tazzzo.test"));
        JsonNode body = me(token).getBody();

        assertThat(body.path("email").asText()).isEqualTo(GoogleIdTokens.WRITER_EMAIL_LABEL);
        assertThat(body.path("actorId").asText()).isEqualTo("google:" + GoogleIdTokens.WRITER);
    }

    @Test
    void roles_come_from_the_principal_never_from_token_claims() {
        String token = TOKENS.token(GoogleIdTokens.READER, Instant.now(), c -> {
            c.put("roles", List.of("cms-writer"));
            c.put("groups", List.of("cms-writer"));
        });
        assertThat(roles(me(token).getBody())).containsExactly("reader");
    }

    @Test
    void the_human_response_leaks_no_credential_subject_token_or_claim_data() {
        String token = human(GoogleIdTokens.WRITER);
        ResponseEntity<JsonNode> res = me(token);
        String raw = res.getBody().toString();

        assertThat(fieldNames(res.getBody())).containsExactly("actorType", "actorId", "email", "roles");
        for (String forbidden : new String[]{"credentialId", "credential_id", "oidc:google", "\"sub\"", "subject", "claims",
                "iss", "aud", "\"hd\"", "iat", "exp", "nonce", "kid", "token", "name",
                "picture", "email_verified", GoogleIdTokens.AUDIENCE, "accounts.google.com"}) {
            assertThat(raw).as(forbidden).doesNotContain(forbidden);
        }
        for (String segment : token.split("\\.")) {
            assertThat(raw).doesNotContain(segment);
        }
    }

    // ---------- service accounts ----------

    @Test
    void the_read_service_token_gets_its_service_identity_and_no_email() {
        ResponseEntity<JsonNode> res = me(READ_TOKEN);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fieldNames(res.getBody())).containsExactly("actorType", "actorId", "roles");
        assertThat(res.getBody().path("actorType").asText()).isEqualTo("SERVICE_ACCOUNT");
        assertThat(res.getBody().path("actorId").asText()).isEqualTo("service:reader");
        assertThat(roles(res.getBody())).containsExactly("reader");
        assertThat(res.getBody().has("email")).as("absent, not null").isFalse();
        assertThat(res.getBody().toString()).doesNotContain("shared-token").doesNotContain("@").doesNotContain(READ_TOKEN);
    }

    @Test
    void the_cms_service_token_gets_its_service_identity_and_no_email() {
        ResponseEntity<JsonNode> res = me(CMS_TOKEN);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fieldNames(res.getBody())).containsExactly("actorType", "actorId", "roles");
        assertThat(res.getBody().path("actorId").asText()).isEqualTo("service:cms-writer");
        assertThat(roles(res.getBody())).containsExactly("cms-writer");
        assertThat(res.getBody().toString()).doesNotContain("@").doesNotContain(CMS_TOKEN);
    }

    // ---------- refusals happen in the auth layer, before /me ----------

    @Test
    void a_missing_or_invalid_credential_is_401_with_the_existing_envelope() {
        for (String token : new String[]{null, "not-a-token",
                TOKENS.token(GoogleIdTokens.WRITER, Instant.now().minusSeconds(7200))}) {
            ResponseEntity<JsonNode> res = me(token);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
            assertThat(res.getBody().path("error").path("request_id").asText()).startsWith("req_");
            assertThat(res.getBody().has("actorId")).isFalse();
        }
    }

    @Test
    void a_non_allowlisted_or_disabled_human_is_403_before_me() {
        for (String sub : new String[]{GoogleIdTokens.STRANGER, GoogleIdTokens.DISABLED}) {
            ResponseEntity<JsonNode> res = me(human(sub));
            assertThat(res.getStatusCode()).as(sub).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(res.getBody().toString()).doesNotContain(sub).doesNotContain("@");
        }
    }

    // ---------- a pure read, on the INTERNAL surface, in the generated internal API docs ----------

    @Test
    void me_is_internal_and_writes_nothing() {
        assertThat(SurfaceClassifier.classify(ME)).isEqualTo(SurfaceClassifier.Surface.INTERNAL);
        Map<String, Long> before = counts();

        me(human(GoogleIdTokens.WRITER));
        me(human(GoogleIdTokens.READER));
        me(CMS_TOKEN);
        me(READ_TOKEN);

        assertThat(counts()).isEqualTo(before);
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new TreeMap<>();
        for (String name : db.listCollectionNames()) {
            counts.put(name, db.getCollection(name).countDocuments());
        }
        for (String ledger : new String[]{"product_events", "node_events", "domain_events"}) {
            counts.putIfAbsent(ledger, db.getCollection(ledger).countDocuments(new Document()));
        }
        return counts;
    }

    @Test
    void the_generated_internal_api_docs_describe_me() {
        assertThat(get("/v3/api-docs", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> docs = get("/v3/api-docs", READ_TOKEN, JsonNode.class);

        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode op = docs.getBody().path("paths").path(ME).path("get");
        assertThat(op.isMissingNode()).as("GET " + ME + " is documented").isFalse();
        String ref = op.path("responses").path("200").path("content").findValue("$ref").asText();
        assertThat(ref).endsWith("/AdminMeResponse");
        JsonNode properties = docs.getBody().path("components").path("schemas").path("AdminMeResponse").path("properties");
        assertThat(fieldNames(properties)).containsExactlyInAnyOrder("actorType", "actorId", "email", "roles");
    }
}
