package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.sun.net.httpserver.HttpServer;
import com.tazzzo.admin.audit.AuditEventQuery;
import com.tazzzo.admin.auth.GoogleIdTokens;
import com.tazzzo.catalog.schema.TaxonomyLoader;
import com.tazzzo.catalog.tx.TaxonomyChangeService;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorDocuments;
import com.tazzzo.common.audit.ActorType;
import com.tazzzo.common.audit.TestActors;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/admin/audit-events} over real HTTP against real Mongo, with the production auth wiring (Google OIDC
 * against a loopback key server, never Google, plus the shared service tokens). Rows are seeded straight into the
 * persisted ledgers in the exact shape the write paths produce (actor via {@link ActorDocuments}), plus one real attributed
 * write over HTTP, so the read model is proven against the existing audit data rather than a parallel one.
 */
class AdminAuditEventsIT extends AbstractApiIT {

    static final String PATH = "/api/v1/admin/audit-events";
    static final String WRITER_AUDITOR = "110000000000000000006";
    static final String DISABLED_AUDITOR = "110000000000000000007";
    static final String BASMATI = "TZV-000001";
    static final GoogleIdTokens TOKENS = new GoogleIdTokens("kid-audit");
    static final HttpServer KEYS;
    static final String SECRET_TOKEN = "Bearer SEEDED-SECRET-TOKEN-VALUE";
    static final String SECRET_SESSION = "SEEDED-SESSION-ID-VALUE";
    static final String SECRET_EMAIL = "private.person@example.com";
    static final List<String> FIELDS = List.of("id", "occurredAt", "action", "targetType", "targetId", "actorType",
            "actorId", "credentialId", "requestId");

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
                {GoogleIdTokens.AUDITOR, "auditor@tazzzo.test", "audit-reader", "true"},
                {WRITER_AUDITOR, "both@tazzzo.test", "cms-writer,audit-reader", "true"},
                {DISABLED_AUDITOR, "gone@tazzzo.test", "audit-reader", "false"},
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

    @Autowired TaxonomyLoader loader;
    @Autowired TaxonomyChangeService releases;
    @Autowired MeterRegistry registry;

    @AfterAll
    void stopKeys() {
        KEYS.stop(0);
    }

    @BeforeAll
    void setup() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        releases.recordBaseline(TestActors.TEST, "0.9.0");
    }

    @BeforeEach
    void clearLedgers() {
        for (String ledger : List.of("product_events", "node_events", "domain_events")) {
            db.getCollection(ledger).deleteMany(new Document());
        }
    }

    // ---------- fixtures ----------

    static String auditor() {
        return TOKENS.token(GoogleIdTokens.AUDITOR, Instant.now());
    }

    static String human(String sub) {
        return TOKENS.token(sub, Instant.now());
    }

    static String rid(int n) {
        return String.format("req_%020x", n);
    }

    static Actor humanActor(int n) {
        return new Actor(ActorType.HUMAN_ADMIN, "google:" + GoogleIdTokens.WRITER, GoogleIdTokens.CREDENTIAL_ID, rid(n));
    }

    static Actor serviceActor(int n) {
        return new Actor(ActorType.SERVICE_ACCOUNT, "service:cms-writer", "shared-token:cms-writer", rid(n));
    }

    static final Actor SYSTEM = Actor.system("system:taint-worker");

    /** A detail map shaped like the worst a ledger could hold: the API must never echo any of it. */
    static Document secretDetail() {
        return new Document("authorization", SECRET_TOKEN).append("session_id", SECRET_SESSION)
                .append("email", SECRET_EMAIL).append("stack", "java.lang.IllegalStateException at x.y(Z.java:1)");
    }

    ObjectId productEvent(Instant at, Actor actor, String type, String productId) {
        return insert("product_events", new Document("type", type).append("product_id", productId)
                .append("detail", secretDetail()).append("at", Date.from(at)), actor);
    }

    ObjectId nodeEvent(Instant at, Actor actor, String event, String nodeId) {
        return insert("node_events", new Document("node_id", nodeId).append("event", event).append("release_id", "0.9.0")
                .append("detail", secretDetail()).append("at", Date.from(at)), actor);
    }

    ObjectId domainEvent(Instant at, Actor actor, String type, String aggregateType, String aggregateId) {
        return insert("domain_events", new Document("aggregate_type", aggregateType).append("aggregate_id", aggregateId)
                .append("type", type).append("detail", secretDetail()).append("at", Date.from(at)), actor);
    }

    private ObjectId insert(String ledger, Document doc, Actor actor) {
        ObjectId id = new ObjectId();
        db.getCollection(ledger).insertOne(ActorDocuments.appendTo(doc.append("_id", id), actor));
        return id;
    }

    static String query(String... kv) {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            q.append(q.isEmpty() ? "?" : "&").append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return q.toString();
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String pathAndQuery, String token) {
        // URI (never a template string), so '{', '$' and '[' reach the server exactly as encoded.
        return rest.exchange(URI.create(url(pathAndQuery)), method, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    ResponseEntity<JsonNode> audit(String token, String... kv) {
        return call(HttpMethod.GET, PATH + query(kv), token);
    }

    static List<String> ids(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("items").forEach(i -> ids.add(i.path("id").asText()));
        return ids;
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    double outcome(String outcome) {
        var c = registry.find("admin_audit_read").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    /** Pages through every result with {@code limit}, asserting each page is at most that size. */
    List<String> drain(int limit, String... filters) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            List<String> kv = new ArrayList<>(List.of(filters));
            kv.add("limit");
            kv.add(String.valueOf(limit));
            if (cursor != null) {
                kv.add("cursor");
                kv.add(cursor);
            }
            ResponseEntity<JsonNode> res = audit(auditor(), kv.toArray(String[]::new));
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().path("items").size()).isLessThanOrEqualTo(limit);
            all.addAll(ids(res.getBody()));
            JsonNode next = res.getBody().path("nextCursor");
            if (next.isNull()) {
                return all;
            }
            cursor = next.asText();
        }
        throw new AssertionError("pagination did not terminate");
    }

    static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    // ---------- authentication and authorization ----------

    @Test
    void unauthenticated_is_401_and_no_body_data() {
        productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        ResponseEntity<JsonNode> res = audit(null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(res.getBody().has("items")).isFalse();
        assertThat(audit("not-a-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void humans_without_audit_reader_are_403_whatever_their_other_roles() {
        productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        double before = outcome("forbidden");
        for (String sub : List.of(GoogleIdTokens.READER, GoogleIdTokens.WRITER)) {
            ResponseEntity<JsonNode> res = audit(human(sub));
            assertThat(res.getStatusCode()).as(sub).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(res.getBody().path("error").path("message").asText()).isEqualTo("audit read not granted");
            assertThat(res.getBody().has("items")).isFalse();
        }
        assertThat(outcome("forbidden") - before).isEqualTo(2.0);
    }

    @Test
    void service_accounts_are_never_granted_audit_read() {
        productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        for (String token : List.of(CMS_TOKEN, READ_TOKEN)) {
            ResponseEntity<JsonNode> res = audit(token);
            assertThat(res.getStatusCode()).as(token).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(res.getBody().has("items")).isFalse();
        }
    }

    @Test
    void a_disabled_auditor_is_403_at_the_allowlist() {
        assertThat(audit(human(DISABLED_AUDITOR)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void permission_is_checked_before_any_parameter_so_invalid_input_is_still_403() {
        ResponseEntity<JsonNode> res = audit(human(GoogleIdTokens.READER), "$where", "1", "limit", "9999");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void an_auditor_reads_and_an_auditor_who_also_writes_reads_too() {
        productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        double before = outcome("served");
        assertThat(audit(auditor()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit(human(WRITER_AUDITOR)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcome("served") - before).isEqualTo(2.0);
    }

    @Test
    void audit_reader_alone_grants_no_catalogue_read_and_no_write() {
        assertThat(get("/api/v1/products/TZP-NOPE", auditor(), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/taxonomy/nodes/" + BASMATI, auditor(), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/products", Map.of("id", "TZP-X"), auditor(), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/admin/me", auditor(), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // the narrow allowance is an EXACT path match: a decorated variant fails closed
        assertThat(call(HttpMethod.GET, PATH + "/", auditor()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void no_http_method_can_change_an_audit_row() {
        ObjectId id = productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        long before = db.getCollection("product_events").countDocuments();
        for (HttpMethod m : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            // a principal that may not write: refused by the admin filter
            assertThat(call(m, PATH, auditor()).getStatusCode()).as(m + " auditor").isEqualTo(HttpStatus.FORBIDDEN);
            // a principal that may write: there is simply no such mapping
            assertThat(call(m, PATH, CMS_TOKEN).getStatusCode()).as(m + " cms").isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(call(m, PATH, human(WRITER_AUDITOR)).getStatusCode()).as(m + " human writer+auditor")
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(call(m, PATH + "/pe_" + id.toHexString(), human(WRITER_AUDITOR)).getStatusCode())
                    .as(m + " by id").isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
        }
        assertThat(db.getCollection("product_events").countDocuments()).isEqualTo(before);
        assertThat(db.getCollection("product_events").find(Filters.eq("_id", id)).first().getString("type"))
                .isEqualTo("CREATED");
    }

    // ---------- projection ----------

    @Test
    void the_response_is_the_allowlisted_projection_and_never_the_detail() {
        productEvent(T0, humanActor(1), "PRICE_UPDATED", "TZP-1");
        ResponseEntity<JsonNode> res = audit(auditor());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fieldNames(res.getBody())).containsExactly("items", "nextCursor");
        JsonNode item = res.getBody().path("items").get(0);
        assertThat(fieldNames(item)).containsExactlyElementsOf(FIELDS);
        assertThat(item.path("occurredAt").asText()).isEqualTo("2026-10-01T12:00:00Z");
        assertThat(item.path("action").asText()).isEqualTo("PRICE_UPDATED");
        assertThat(item.path("targetType").asText()).isEqualTo("product");
        assertThat(item.path("targetId").asText()).isEqualTo("TZP-1");
        assertThat(item.path("actorType").asText()).isEqualTo("HUMAN_ADMIN");
        assertThat(item.path("actorId").asText()).isEqualTo("google:" + GoogleIdTokens.WRITER);
        assertThat(item.path("credentialId").asText()).isEqualTo(GoogleIdTokens.CREDENTIAL_ID);
        assertThat(item.path("requestId").asText()).isEqualTo(rid(1));
        assertThat(item.path("id").asText()).startsWith("pe_");

        String raw = res.getBody().toString();
        for (String forbidden : List.of(SECRET_TOKEN, "SEEDED-SECRET", SECRET_SESSION, SECRET_EMAIL, "detail",
                "authorization", "session", "stack", "release_id", "_id")) {
            assertThat(raw).as(forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    void every_ledger_maps_to_the_same_shape() {
        productEvent(T0.plusSeconds(3), humanActor(1), "CREATED", "TZP-1");
        nodeEvent(T0.plusSeconds(2), serviceActor(2), "renamed", BASMATI);
        domainEvent(T0.plusSeconds(1), SYSTEM, "PIN_UPSERTED", "serviceability_pin", "560001");

        JsonNode items = audit(auditor()).getBody().path("items");
        assertThat(items).hasSize(3);
        for (JsonNode i : items) {
            assertThat(fieldNames(i)).containsExactlyElementsOf(FIELDS);
        }
        assertThat(items.get(0).path("targetType").asText()).isEqualTo("product");
        assertThat(items.get(1).path("id").asText()).startsWith("ne_");
        assertThat(items.get(1).path("action").asText()).isEqualTo("renamed");
        assertThat(items.get(1).path("targetType").asText()).isEqualTo("taxonomy_node");
        assertThat(items.get(1).path("targetId").asText()).isEqualTo(BASMATI);
        assertThat(items.get(1).path("actorType").asText()).isEqualTo("SERVICE_ACCOUNT");
        assertThat(items.get(1).path("credentialId").asText()).isEqualTo("shared-token:cms-writer");
        assertThat(items.get(2).path("id").asText()).startsWith("de_");
        assertThat(items.get(2).path("targetType").asText()).isEqualTo("serviceability_pin");
        assertThat(items.get(2).path("targetId").asText()).isEqualTo("560001");
        assertThat(items.get(2).path("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(items.get(2).path("actorId").asText()).isEqualTo("system:taint-worker");
        assertThat(items.get(2).path("credentialId").isNull()).as("never manufactured").isTrue();
        assertThat(items.get(2).path("requestId").isNull()).isTrue();
    }

    @Test
    void unattributed_events_are_never_returned() {
        db.getCollection("product_events").insertOne(new Document("type", "CREATED").append("product_id", "TZP-LEGACY")
                .append("detail", new Document()).append("at", Date.from(T0)));
        db.getCollection("price_events").insertOne(new Document("product_id", "TZP-LEGACY").append("ts", Date.from(T0)));
        ObjectId attributed = productEvent(T0.minusSeconds(1), humanActor(1), "CREATED", "TZP-1");

        assertThat(ids(audit(auditor()).getBody())).containsExactly("pe_" + attributed.toHexString());
    }

    @Test
    void a_real_attributed_http_write_is_readable_with_its_server_request_id() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "TZP-AUDREAD-1");
        m.put("productType", "single");
        m.put("identityType", "internal");
        m.put("internalKey", "audread|1");
        m.put("brandCode", "BR-AUD");
        m.put("title", "Audit read 1");
        m.put("verticalId", BASMATI);
        m.put("releaseId", "0.9.0");
        m.put("classificationStatus", "provisional");
        m.put("attributes", Map.of("pack_size", 5, "pack_unit", "kg"));
        m.put("evidenceRefs", List.of());
        ResponseEntity<JsonNode> created = post("/api/v1/products", m, human(GoogleIdTokens.WRITER), JsonNode.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        String requestId = created.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).matches(AuditEventQuery.REQUEST_ID);

        JsonNode items = audit(auditor(), "requestId", requestId).getBody().path("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(1);
        for (JsonNode i : items) {
            assertThat(i.path("requestId").asText()).isEqualTo(requestId);
            assertThat(i.path("actorType").asText()).isEqualTo("HUMAN_ADMIN");
            assertThat(i.path("actorId").asText()).isEqualTo("google:" + GoogleIdTokens.WRITER);
            assertThat(i.path("credentialId").asText()).isEqualTo(GoogleIdTokens.CREDENTIAL_ID);
            assertThat(i.path("targetId").asText()).isEqualTo("TZP-AUDREAD-1");
        }
    }

    // ---------- ordering and pagination ----------

    @Test
    void newest_first_with_a_default_limit_of_50() {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            expected.add(0, "pe_" + productEvent(T0.plusSeconds(i), humanActor(i), "CREATED", "TZP-" + i).toHexString());
        }
        JsonNode body = audit(auditor()).getBody();
        assertThat(ids(body)).containsExactlyElementsOf(expected.subList(0, 50));
        assertThat(body.path("nextCursor").isTextual()).isTrue();
    }

    @Test
    void limit_bounds_are_enforced() {
        for (int i = 0; i < 120; i++) {
            productEvent(T0.plusSeconds(i), humanActor(i), "CREATED", "TZP-" + i);
        }
        assertThat(audit(auditor(), "limit", "1").getBody().path("items")).hasSize(1);
        assertThat(audit(auditor(), "limit", "100").getBody().path("items")).hasSize(100);
        double before = outcome("invalid");
        for (String bad : List.of("0", "101", "1000", "-1", "abc", "1.5", "1e2", " 5", "0x10", "99999999999")) {
            ResponseEntity<JsonNode> res = audit(auditor(), "limit", bad);
            assertThat(res.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("MALFORMED_REQUEST");
        }
        assertThat(outcome("invalid") - before).isEqualTo(10.0);
    }

    @Test
    void identical_timestamps_across_ledgers_page_stably_with_no_duplicate_or_gap() {
        List<String> expected = new ArrayList<>();
        // 5 per ledger, ALL at the same millisecond: order is (rank ASC, _id DESC) = pe newest id first, then ne, then de
        List<String> pe = new ArrayList<>();
        List<String> ne = new ArrayList<>();
        List<String> de = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pe.add(0, "pe_" + productEvent(T0, humanActor(i), "CREATED", "TZP-" + i).toHexString());
            ne.add(0, "ne_" + nodeEvent(T0, humanActor(10 + i), "renamed", BASMATI).toHexString());
            de.add(0, "de_" + domainEvent(T0, SYSTEM, "PIN_UPSERTED", "serviceability_pin", "56000" + i).toHexString());
        }
        expected.add("pe_" + productEvent(T0.plusSeconds(1), humanActor(99), "CREATED", "TZP-NEW").toHexString());
        expected.addAll(pe);
        expected.addAll(ne);
        expected.addAll(de);
        expected.add("pe_" + productEvent(T0.minusSeconds(1), humanActor(98), "CREATED", "TZP-OLD").toHexString());

        for (int limit : new int[]{1, 2, 3, 4, 7, 100}) {
            assertThat(drain(limit)).as("limit " + limit).containsExactlyElementsOf(expected);
        }
        // the same page twice is the same page
        JsonNode first = audit(auditor(), "limit", "4").getBody();
        assertThat(audit(auditor(), "limit", "4").getBody()).isEqualTo(first);
    }

    @Test
    void events_committed_during_pagination_never_shift_later_pages() {
        List<String> original = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            original.add(0, "pe_" + productEvent(T0.plusSeconds(i), humanActor(i), "CREATED", "TZP-" + i).toHexString());
        }
        JsonNode page1 = audit(auditor(), "limit", "3").getBody();
        assertThat(ids(page1)).containsExactlyElementsOf(original.subList(0, 3));

        // new events land between the reads: newer than everything (the normal case) AND at the cursor's own instant
        String newest = "pe_" + productEvent(Instant.now(), humanActor(50), "CREATED", "TZP-LATE").toHexString();
        String sameInstant = "pe_" + productEvent(T0.plusSeconds(3), humanActor(51), "CREATED", "TZP-SAME").toHexString();

        JsonNode page2 = audit(auditor(), "limit", "3", "cursor", page1.path("nextCursor").asText()).getBody();
        assertThat(ids(page2)).containsExactlyElementsOf(original.subList(3, 6));
        assertThat(ids(page2)).doesNotContain(newest, sameInstant);
        assertThat(page2.path("nextCursor").isNull()).isTrue();

        // a fresh traversal sees them in their place in the total order
        assertThat(drain(100)).containsExactly(newest, original.get(0), original.get(1), sameInstant, original.get(2),
                original.get(3), original.get(4), original.get(5));
    }

    @Test
    void the_last_page_has_a_null_cursor_and_an_empty_result_is_an_empty_page() {
        JsonNode empty = audit(auditor()).getBody();
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("nextCursor").isNull()).isTrue();

        productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        productEvent(T0.plusSeconds(1), humanActor(2), "CREATED", "TZP-2");
        assertThat(audit(auditor(), "limit", "2").getBody().path("nextCursor").isNull()).as("exactly limit rows").isTrue();
    }

    // ---------- filters ----------

    @Test
    void filters_are_exact_and_combine_with_and() {
        Actor other = new Actor(ActorType.HUMAN_ADMIN, "google:" + GoogleIdTokens.READER, GoogleIdTokens.CREDENTIAL_ID,
                rid(500));
        String a = "pe_" + productEvent(T0.plusSeconds(5), humanActor(1), "PRICE_UPDATED", "TZP-1").toHexString();
        String b = "pe_" + productEvent(T0.plusSeconds(4), other, "PRICE_UPDATED", "TZP-1").toHexString();
        String c = "ne_" + nodeEvent(T0.plusSeconds(3), serviceActor(2), "renamed", BASMATI).toHexString();
        String d = "de_" + domainEvent(T0.plusSeconds(2), SYSTEM, "PIN_UPSERTED", "serviceability_pin", "560001")
                .toHexString();
        String e = "pe_" + productEvent(T0.plusSeconds(1), humanActor(3), "CREATED", "TZP-10").toHexString();

        assertThat(ids(audit(auditor(), "actorType", "HUMAN_ADMIN").getBody())).containsExactly(a, b, e);
        assertThat(ids(audit(auditor(), "actorType", "SERVICE_ACCOUNT").getBody())).containsExactly(c);
        assertThat(ids(audit(auditor(), "actorType", "SYSTEM").getBody())).containsExactly(d);
        assertThat(ids(audit(auditor(), "actorId", "google:" + GoogleIdTokens.WRITER).getBody())).containsExactly(a, e);
        assertThat(ids(audit(auditor(), "actorId", "system:taint-worker").getBody())).containsExactly(d);
        assertThat(ids(audit(auditor(), "action", "PRICE_UPDATED").getBody())).containsExactly(a, b);
        assertThat(ids(audit(auditor(), "action", "renamed").getBody())).containsExactly(c);
        assertThat(ids(audit(auditor(), "action", "price_updated").getBody())).as("case-sensitive exact").isEmpty();
        assertThat(ids(audit(auditor(), "targetType", "product").getBody())).containsExactly(a, b, e);
        assertThat(ids(audit(auditor(), "targetType", "product", "targetId", "TZP-1").getBody())).containsExactly(a, b);
        assertThat(ids(audit(auditor(), "targetType", "product", "targetId", "TZP").getBody())).as("no prefix").isEmpty();
        assertThat(ids(audit(auditor(), "targetType", "taxonomy_node", "targetId", BASMATI).getBody()))
                .containsExactly(c);
        assertThat(ids(audit(auditor(), "targetType", "serviceability_pin").getBody())).containsExactly(d);
        assertThat(ids(audit(auditor(), "targetType", "unknown_kind").getBody())).isEmpty();
        assertThat(ids(audit(auditor(), "actorType", "HUMAN_ADMIN", "action", "PRICE_UPDATED", "actorId",
                "google:" + GoogleIdTokens.READER).getBody())).containsExactly(b);
    }

    @Test
    void request_id_is_an_exact_match_with_a_validated_format() {
        String hit = "pe_" + productEvent(T0.plusSeconds(1), humanActor(0xabc), "CREATED", "TZP-1").toHexString();
        productEvent(T0, humanActor(0xabcd), "CREATED", "TZP-2");

        assertThat(ids(audit(auditor(), "requestId", rid(0xabc)).getBody())).containsExactly(hit);
        assertThat(ids(audit(auditor(), "requestId", rid(0xabe)).getBody())).isEmpty();
        for (String bad : List.of("req_abc", "req_" + "A".repeat(20), "REQ_00000000000000000abc",
                rid(0xabc) + "0", "req_.*", "req_0000000000000000abc|", "{\"$ne\":null}")) {
            assertThat(audit(auditor(), "requestId", bad).getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void from_and_to_are_inclusive_utc_bounds_and_must_be_ordered() {
        String early = "pe_" + productEvent(T0, humanActor(1), "CREATED", "TZP-1").toHexString();
        String mid = "pe_" + productEvent(T0.plusSeconds(60), humanActor(2), "CREATED", "TZP-2").toHexString();
        String late = "pe_" + productEvent(T0.plusSeconds(120), humanActor(3), "CREATED", "TZP-3").toHexString();

        assertThat(ids(audit(auditor(), "from", "2026-10-01T12:01:00Z").getBody())).containsExactly(late, mid);
        assertThat(ids(audit(auditor(), "to", "2026-10-01T12:01:00Z").getBody())).containsExactly(mid, early);
        assertThat(ids(audit(auditor(), "from", "2026-10-01T12:01:00Z", "to", "2026-10-01T12:01:00Z").getBody()))
                .containsExactly(mid);
        assertThat(ids(audit(auditor(), "from", "2026-10-01T12:00:00.001Z", "to", "2026-10-01T12:01:59.999Z")
                .getBody())).containsExactly(mid);

        ResponseEntity<JsonNode> reversed = audit(auditor(), "from", "2026-10-01T12:02:00Z", "to", "2026-10-01T12:00:00Z");
        assertThat(reversed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reversed.getBody().path("error").path("message").asText()).isEqualTo("from must not be after to");
        for (String bad : List.of("2026-10-01", "2026-10-01T12:00:00", "2026-10-01T12:00:00+05:30",
                "2026-10-01T12:00:00.0001Z", "1696161600000", "2026-13-01T00:00:00Z", "now")) {
            assertThat(audit(auditor(), "from", bad).getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void target_id_without_target_type_is_rejected() {
        assertThat(audit(auditor(), "targetId", "TZP-1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- injection and abuse ----------

    @Test
    void no_operator_regex_sort_or_json_filter_is_accepted() {
        productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        String[][] attacks = {
                {"actorId[$ne]", "x"}, {"actorId[$gt]", ""}, {"$where", "1"}, {"$regex", ".*"},
                {"actorId", "{\"$ne\":null}"}, {"actorId", "{\"$gt\":\"\"}"}, {"action", "{\"$regex\":\".*\"}"},
                {"action", ".*"}, {"action", "^CREATED$"}, {"actorId", "google:.*"}, {"targetType", "product|x"},
                {"filter", "{}"}, {"query", "{\"actor.id\":{\"$exists\":true}}"}, {"sort", "at"},
                {"sort", "{\"at\":1}"}, {"orderBy", "at"}, {"projection", "detail"}, {"fields", "detail"},
                {"actor.id", "google:1"}, {"detail.authorization", "x"}, {"offset", "10"}, {"page", "2"},
                {"actorType", "human_admin"}, {"actorType", "ADMIN"}, {"actorId", "admin:ceo"},
                {"targetId", "TZP-1"}, {"targetType", "Product"},
        };
        for (String[] attack : attacks) {
            ResponseEntity<JsonNode> res = audit(auditor(), attack[0], attack[1]);
            assertThat(res.getStatusCode()).as(attack[0] + "=" + attack[1]).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("MALFORMED_REQUEST");
            assertThat(res.getBody().toString()).doesNotContain("TZP-1");
        }
    }

    @Test
    void repeated_or_empty_parameters_are_rejected() {
        assertThat(call(HttpMethod.GET, PATH + "?action=CREATED&action=DELETED", auditor()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, PATH + "?limit=1&limit=100", auditor()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, PATH + "?action=", auditor()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, PATH + "?cursor", auditor()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void a_malformed_or_foreign_cursor_is_400() {
        productEvent(T0, humanActor(1), "CREATED", "TZP-1");
        productEvent(T0.plusSeconds(1), humanActor(2), "CREATED", "TZP-2");
        String good = audit(auditor(), "limit", "1").getBody().path("nextCursor").asText();
        assertThat(audit(auditor(), "limit", "1", "cursor", good).getStatusCode()).isEqualTo(HttpStatus.OK);

        String b64 = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1|1|pe|0123456789abcdef01234567|0000000000000000".getBytes(StandardCharsets.US_ASCII));
        String overflow = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1|9999999999999999999|pe|0123456789abcdef01234567|0".getBytes(StandardCharsets.US_ASCII));
        for (String bad : List.of("x", "!!!", good + "A", good.substring(1), "e30", b64, overflow, "a".repeat(129),
                "djF8MXxwZXx8", "%00", good + "=")) {
            ResponseEntity<JsonNode> res = audit(auditor(), "limit", "1", "cursor", bad);
            assertThat(res.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("MALFORMED_REQUEST");
        }
        // a cursor is bound to the filters it was issued for
        ResponseEntity<JsonNode> foreign = audit(auditor(), "limit", "1", "cursor", good, "action", "CREATED");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(foreign.getBody().path("error").path("message").asText())
                .isEqualTo("cursor does not belong to these filters");
    }

    @Test
    void error_messages_never_echo_the_supplied_value() {
        String probe = "google:PROBE_VALUE_" + "Z".repeat(300);
        ResponseEntity<JsonNode> res = audit(auditor(), "actorId", probe);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().toString()).doesNotContain("PROBE_VALUE");
        assertThat(audit(auditor(), "SECRETNAME", "x").getBody().toString()).doesNotContain("SECRETNAME");
    }

    @Test
    void a_corrupt_ledger_row_is_a_generic_500_that_leaks_nothing() {
        db.getCollection("product_events").insertOne(new Document("type", "CREATED").append("product_id", "TZP-BAD")
                .append("at", Date.from(T0)).append("actor", new Document("type", "HUMAN_ADMIN")
                        .append("id", "google:1").append("smuggled", SECRET_TOKEN)));
        ResponseEntity<JsonNode> res = audit(auditor());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().path("error").path("code").asText()).isEqualTo("INTERNAL");
        assertThat(res.getBody().toString()).doesNotContain(SECRET_TOKEN).doesNotContain("TZP-BAD");
    }
}
