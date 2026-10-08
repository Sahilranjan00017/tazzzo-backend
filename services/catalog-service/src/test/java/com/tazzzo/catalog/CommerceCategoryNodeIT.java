package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import com.tazzzo.common.audit.TestActors;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/categories/{id}} over real HTTP, real Mongo, real Redis: one consumer-visible node
 * by id, visible exactly when CHILDREN would answer 200 for it, charged like CHILDREN with no
 * candidates ({@code 1 + |scope|}), under its own {@code commerce_node} label.
 *
 * <p>Fixture: the frozen seed recorded as release R1, then eligible products under Basmati Rice
 * ({@code TZV-000001}) and Salt ({@code TZV-000057}). Atta ({@code TZC-000002}) stays consumer-empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceCategoryNodeIT extends AbstractConsumerIT {

    private static final String SNAPSHOTS = "taxonomy_snapshot_nodes";
    private static final String CACHE_PUBLIC = "public, max-age=300, stale-while-revalidate=60";
    private static final String STAPLES = "TZS-000001";
    private static final String C_RICE = "TZC-000001";
    private static final String C_ATTA = "TZC-000002";      // unstocked
    private static final String G_BASMATI = "TZG-000001";
    private static final String V_BASMATI = "TZV-000001";
    private static final String G_SALT = "TZG-000013";
    private static final String V_SALT = "TZV-000057";

    @Autowired MeterRegistry registry;
    @Autowired SnapshotTaxonomyReader snapshots;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_commerce_category_node_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        // FIXTURE VALUES ONLY — not production recommendations (Q5-c).
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        eligibleProduct("TZP-BAS1", V_BASMATI);
        eligibleProduct("TZP-SALT1", V_SALT);
        assertThat(row(G_SALT).getString("parent_id")).isEqualTo("TZC-000005");
        assertThat(row(V_SALT).getString("parent_id")).isEqualTo(G_SALT);
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    private Document row(String nodeId) {
        return db.getCollection(SNAPSHOTS).find(and(eq("release_id", "R1"), eq("node_id", nodeId))).first();
    }

    private void setStatus(String nodeId, String status) {
        db.getCollection(SNAPSHOTS).updateOne(and(eq("release_id", "R1"), eq("node_id", nodeId)),
                new Document("$set", new Document("status", status)));
    }

    private ResponseEntity<JsonNode> node(String id) {
        return get("/v1/categories/" + id, JsonNode.class);
    }

    private double charged(String route) {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", route).summary();
        return s == null ? 0 : s.totalAmount();
    }

    private double outcome(String route, String outcome) {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", route, "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private void assertError(ResponseEntity<JsonNode> res, int status, String code) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(status);
        assertThat(res.getBody().at("/code").asText()).isEqualTo(code);
        assertThat(res.getBody().at("/message").asText()).isNotBlank();
        assertThat(res.getBody().at("/requestId").asText()).startsWith("req_");
        assertThat(res.getBody().has("retryable")).isTrue();
        assertThat(res.getHeaders().getFirst("Cache-Control")).as("an error is never publicly cacheable")
                .isEqualTo("no-store");
        assertThat(res.getHeaders().getFirst("ETag")).isNull();
    }

    // ---------- found ----------

    @Test
    void a_visible_vertical_answers_its_id_name_release_and_request_id_and_nothing_else() {
        ResponseEntity<JsonNode> res = node(V_BASMATI);

        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(200);
        JsonNode body = res.getBody();
        assertThat(body.at("/id").asText()).isEqualTo(V_BASMATI);
        assertThat(body.at("/name").asText()).isEqualTo("Basmati Rice");
        assertThat(body.at("/resolvedReleaseId").asText()).isEqualTo("R1");
        assertThat(body.at("/requestId").asText()).startsWith("req_");
        assertThat(body.fieldNames()).toIterable()
                .as("CAT-NODE-1: id and name only, in the ProductDetail-style flat envelope")
                .containsExactlyInAnyOrder("id", "name", "resolvedReleaseId", "requestId");
        assertThat(res.getHeaders().getFirst("X-Request-Id")).isEqualTo(body.at("/requestId").asText());
        assertThat(PRODUCT_FINDS.get()).as("exactly one probe: the node's own").isEqualTo(1);
    }

    @Test
    void every_level_of_a_stocked_branch_is_nameable_by_id() {
        for (String id : List.of(STAPLES, C_RICE, G_BASMATI, V_BASMATI)) {
            ResponseEntity<JsonNode> res = node(id);
            assertThat(res.getStatusCode().value()).as(id + " -> " + res.getBody()).isEqualTo(200);
            assertThat(res.getBody().at("/id").asText()).isEqualTo(id);
            assertThat(res.getBody().at("/name").asText()).as(id).isEqualTo(row(id).getString("name"));
        }
    }

    @Test
    void the_node_and_its_listing_entry_carry_the_same_id_and_name() {
        JsonNode listed = get("/v1/categories/" + G_BASMATI + "/children", JsonNode.class).getBody();
        JsonNode item = listed.get("items").get(0);
        JsonNode body = node(item.get("id").asText()).getBody();
        assertThat(body.get("id")).isEqualTo(item.get("id"));
        assertThat(body.get("name")).isEqualTo(item.get("name"));
    }

    @Test
    void an_explicit_release_is_honoured() {
        assertThat(get("/v1/categories/" + V_BASMATI + "?release=R1", JsonNode.class).getBody()
                .at("/resolvedReleaseId").asText()).isEqualTo("R1");
        ResponseEntity<JsonNode> res = get("/v1/categories/" + V_BASMATI + "?release=NOT-A-REAL-RELEASE", JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isFalse();
        assertThat(res.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    // ---------- not found / hidden ----------

    @Test
    void an_unknown_well_formed_id_is_the_flat_404_charged_1_with_no_product_read() {
        double before = charged("commerce_node");
        ResponseEntity<JsonNode> res = node("TZV-999999");
        assertError(res, 404, "NOT_FOUND");
        assertThat(charged("commerce_node") - before).as("probing for existence is never free").isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).isZero();
    }

    @Test
    void a_consumer_empty_node_is_the_same_404_after_its_own_probe() {
        double before = charged("commerce_node");
        ResponseEntity<JsonNode> res = node(C_ATTA);
        assertError(res, 404, "NOT_FOUND");
        assertThat(charged("commerce_node") - before)
                .isEqualTo(1 + snapshots.consumerVerticalIdsInSubtree("R1", C_ATTA).size());
        assertThat(PRODUCT_FINDS.get()).as("the node's own probe, and nothing else").isEqualTo(1);
    }

    @Test
    void a_deprecated_node_and_everything_beneath_it_is_404_while_the_control_branch_stays_visible() {
        assertThat(node(V_SALT).getStatusCode().value()).as("baseline").isEqualTo(200);
        setStatus(G_SALT, "deprecated");
        try {
            for (String hidden : List.of(G_SALT, V_SALT)) {
                resetCounters();
                double before = charged("commerce_node");
                assertError(node(hidden), 404, "NOT_FOUND");
                assertThat(charged("commerce_node") - before).as(hidden + ": non-reachable costs 1").isEqualTo(1);
                assertThat(PRODUCT_FINDS.get()).as(hidden + ": decided from the snapshot").isZero();
            }
            assertThat(node(V_BASMATI).getStatusCode().value()).as("control branch").isEqualTo(200);
        } finally {
            setStatus(G_SALT, "active");
        }
        assertThat(node(V_SALT).getStatusCode().value()).as("restored").isEqualTo(200);
    }

    // ---------- malformed ----------

    @Test
    void a_malformed_id_is_400_invalid_request_before_anything_is_charged_or_read() {
        for (String bad : List.of("TZC-1", "TZC-0000001", "tzc-000001", "TZX-000001", "TZP-000001",
                "TZC_000001", "TZC-00000A", "TZC-000001x", "TZC-000001.json")) {
            resetCounters();
            double before = charged("commerce_node");
            double invalidBefore = outcome("commerce_node", "invalid_request");
            ResponseEntity<JsonNode> res = node(bad);
            assertThat(res.getStatusCode().value()).as("[" + bad + "] -> " + res.getBody()).isEqualTo(400);
            assertError(res, 400, "INVALID_REQUEST");
            assertThat(charged("commerce_node") - before).as(bad + ": nothing charged").isZero();
            assertThat(outcome("commerce_node", "invalid_request") - invalidBefore).as(bad).isEqualTo(1);
            assertThat(finds(SNAPSHOTS)).as(bad + ": no taxonomy read").isZero();
            assertThat(PRODUCT_FINDS.get()).isZero();
        }
    }

    @Test
    void an_id_that_needs_percent_encoding_never_reaches_the_route() {
        // SurfaceClassifier refuses any percent escape before routing (fail closed), so a space, a
        // control character or a non-ASCII digit is the pre-routing 404, never a taxonomy read.
        for (String bad : List.of(" TZC-000001", "TZC-000001\n", "TZC-\u0661\u0662\u0663\u0664\u0665\u0666")) {
            resetCounters();
            double before = charged("commerce_node");
            ResponseEntity<JsonNode> res = node(bad);
            assertThat(res.getStatusCode().value()).as("[" + bad + "] -> " + res.getBody()).isEqualTo(404);
            assertThat(res.getBody().at("/error/code").asText()).isEqualTo("NO_SUCH_ENDPOINT");
            assertThat(charged("commerce_node") - before).isZero();
            assertThat(finds(SNAPSHOTS)).isZero();
        }
    }

    // ---------- cache ----------

    @Test
    void a_found_node_carries_the_sibling_public_cache_control_and_a_conditional_etag() {
        ResponseEntity<JsonNode> first = node(V_BASMATI);
        assertThat(first.getHeaders().getFirst("Cache-Control")).isEqualTo(CACHE_PUBLIC);
        assertThat(get("/v1/categories/" + V_BASMATI + "/children", JsonNode.class)
                .getHeaders().getFirst("Cache-Control")).as("same as the sibling read").isEqualTo(CACHE_PUBLIC);
        String etag = first.getHeaders().getFirst("ETag");
        assertThat(etag).isNotBlank().startsWith("\"").endsWith("\"");
        assertThat(node(V_BASMATI).getHeaders().getFirst("ETag")).as("deterministic").isEqualTo(etag);
        assertThat(get("/v1/categories/" + V_BASMATI + "/children", JsonNode.class).getHeaders().getFirst("ETag"))
                .as("the route is bound into the hash").isNotEqualTo(etag);
        assertThat(node(C_RICE).getHeaders().getFirst("ETag")).isNotEqualTo(etag);

        HttpHeaders conditional = new HttpHeaders();
        conditional.set("If-None-Match", etag);
        ResponseEntity<JsonNode> notModified = get("/v1/categories/" + V_BASMATI, conditional, JsonNode.class);
        assertThat(notModified.getStatusCode().value()).isEqualTo(304);
        assertThat(notModified.getBody()).isNull();
        assertThat(notModified.getHeaders().getFirst("ETag")).isEqualTo(etag);
        assertThat(notModified.getHeaders().getFirst("Cache-Control")).isEqualTo(CACHE_PUBLIC);
    }

    // ---------- admission ----------

    @Test
    void a_vertical_is_charged_exactly_what_its_children_read_is_charged() {
        double nodeBefore = charged("commerce_node");
        double childrenBefore = charged("commerce_children");
        assertThat(node(V_BASMATI).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/v1/categories/" + V_BASMATI + "/children", JsonNode.class).getStatusCode().value())
                .isEqualTo(200);
        double nodeCost = charged("commerce_node") - nodeBefore;
        assertThat(nodeCost).as("1 + |scope| where a vertical's scope is itself").isEqualTo(2);
        assertThat(charged("commerce_children") - childrenBefore)
                .as("a vertical has no children, so CHILDREN's formula gives the same cost").isEqualTo(nodeCost);
    }

    @Test
    void a_non_leaf_is_charged_one_plus_its_scope_under_its_own_label_never_the_children_label() {
        int scope = snapshots.consumerVerticalIdsInSubtree("R1", C_RICE).size();
        assertThat(scope).as("seed: Rice & Grains spans 25 verticals").isEqualTo(25);
        double nodeBefore = charged("commerce_node");
        double childrenBefore = charged("commerce_children");
        double legacyBefore = charged("children");
        assertThat(node(C_RICE).getStatusCode().value()).isEqualTo(200);
        assertThat(charged("commerce_node") - nodeBefore).isEqualTo(1 + scope);
        assertThat(charged("commerce_children") - childrenBefore).isZero();
        assertThat(charged("children") - legacyBefore).isZero();
    }
}
