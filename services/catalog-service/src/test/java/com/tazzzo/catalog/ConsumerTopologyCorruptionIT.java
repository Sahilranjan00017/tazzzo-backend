package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONSUMER-ERR-2 over real HTTP: a release snapshot whose topology is not a tree is a SERVER fault.
 * All three consumer routes answer flat {@code 503 SERVICE_UNAVAILABLE} with outcome
 * {@code unavailable}, and do no product work and charge nothing — never {@code 400}, never
 * {@code 409}, never a smaller "repaired" tree.
 *
 * <p>{@code taxonomy_snapshot_nodes} has no validator, so the corruption is written directly, as
 * it would be by a bug or an operator: (a) a child row under Staples with NO {@code node_id};
 * (b) a cycle — Staples' own row re-parented under its descendant, the Salt category.
 * Each scenario is restored before the next.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerTopologyCorruptionIT extends AbstractConsumerIT {

    private static final String SNAPSHOTS = "taxonomy_snapshot_nodes";
    private static final String STAPLES = "TZS-000001";
    private static final String FOOD = "TZS-000002";
    private static final String C_SALT = "TZC-000005";
    private static final String G_SALT = "TZG-000013";
    private static final String V_TEA = "TZV-000075";

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_topology_corruption_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> ConsumerListIT.FIXTURE_KEY_B64);
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "1000000");
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        eligibleProduct("TZP-SALT", "TZV-000057");   // Staples visible via Salt
        eligibleProduct("TZP-TEA", V_TEA);           // Food visible, and untouched by either corruption
        assertThat(db.getCollection(SNAPSHOTS).find(and(eq("release_id", "R1"), eq("node_id", G_SALT)))
                .first().getString("parent_id")).isEqualTo(C_SALT);
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    // ---------- corruption fixtures ----------

    private void writeChildWithoutNodeId() {
        db.getCollection(SNAPSHOTS).insertOne(new Document("release_id", "R1")
                .append("parent_id", STAPLES).append("node_type", "category")
                .append("name", "Ghost").append("status", "active"));
    }

    private void removeChildWithoutNodeId() {
        db.getCollection(SNAPSHOTS).deleteMany(and(eq("release_id", "R1"), eq("name", "Ghost")));
    }

    /**
     * Every row has ONE parent_id, so a node is discovered by the walk exactly once -- except the
     * START node, which is in {@code visited} from the beginning. A cycle is therefore visible
     * only when it passes back through the requested node: Staples is made a child of its own
     * descendant, the Salt category. Staples -> ... -> Salt -> Staples.
     */
    private void writeCycle() {
        reparent(STAPLES, C_SALT);
    }

    private void removeCycle() {
        reparent(STAPLES, null);
    }

    private void reparent(String nodeId, String parentId) {
        db.getCollection(SNAPSHOTS).updateOne(and(eq("release_id", "R1"), eq("node_id", nodeId)),
                new Document("$set", new Document("parent_id", parentId)));
    }

    // ---------- helpers ----------

    private double unavailable(String route) {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", route, "outcome", "unavailable").counter();
        return c == null ? 0 : c.count();
    }

    private double charged(String route) {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", route).summary();
        return s == null ? 0 : s.totalAmount();
    }

    private void assertServerFault(String route, String path) {
        resetCounters();
        double unavailableBefore = unavailable(route);
        double chargedBefore = charged(route);

        ResponseEntity<JsonNode> res = get(path, JsonNode.class);

        assertThat(res.getStatusCode().value()).as(path + " -> " + res.getBody()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().get("message").asText()).isEqualTo("service unavailable");
        assertThat(res.getBody().has("error")).as("flat ERR-1").isFalse();
        assertThat(res.getBody().get("request_id").asText()).isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
        assertThat(res.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(unavailable(route) - unavailableBefore).as("outcome=unavailable, once").isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).as("zero product work after a topology failure").isZero();
        assertThat(charged(route) - chargedBefore).as("nothing charged: the scope never resolved").isZero();
    }

    private void assertHealthyElsewhere() {
        // Food is a different subtree: the fault is local to the corrupt branch, not the service.
        assertThat(get("/catalog/v1/categories/" + FOOD + "/children", JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/catalog/v1/categories/" + V_TEA + "/products", JsonNode.class).getStatusCode().value()).isEqualTo(200);
    }

    // ---------- (a) a child row without node_id ----------

    @Test
    void a_malformed_child_without_node_id_is_a_flat_503_on_root_children_and_list() {
        writeChildWithoutNodeId();
        try {
            assertServerFault("root", "/catalog/v1/categories");
            assertServerFault("children", "/catalog/v1/categories/" + STAPLES + "/children");
            assertServerFault("list", "/catalog/v1/categories/" + STAPLES + "/products");
            assertHealthyElsewhere();
        } finally {
            removeChildWithoutNodeId();
        }
        assertThat(get("/catalog/v1/categories", JsonNode.class).getStatusCode().value())
                .as("restored: the fault was the data, not the service").isEqualTo(200);
    }

    // ---------- (b) a cycle ----------

    @Test
    void a_cycle_in_the_snapshot_is_a_flat_503_on_root_children_and_list() {
        writeCycle();
        try {
            assertServerFault("root", "/catalog/v1/categories");
            assertServerFault("children", "/catalog/v1/categories/" + STAPLES + "/children");
            assertServerFault("list", "/catalog/v1/categories/" + STAPLES + "/products");
            // and when the corrupt node is the requested node itself
            assertServerFault("children", "/catalog/v1/categories/" + C_SALT + "/children");
            assertServerFault("list", "/catalog/v1/categories/" + C_SALT + "/products?page_size=5");
            assertHealthyElsewhere();
        } finally {
            removeCycle();
        }
        ResponseEntity<JsonNode> restored = get("/catalog/v1/categories/" + STAPLES + "/children", JsonNode.class);
        assertThat(restored.getStatusCode().value()).isEqualTo(200);
        assertThat(restored.getBody().get("items").findValuesAsText("id")).containsExactly(C_SALT);
    }
}
