package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TAX-REACH-1 over real HTTP: a node requested by id is consumer-reachable only when its whole
 * ancestor path is active up to an active super-category — the branch ROOT would show. Before this
 * hardening an active vertical under a deprecated parent answered 200 on CHILDREN and LIST while
 * ROOT hid the branch.
 *
 * <p>Fixture: seed as R1; Salt (TZV-000057, under Salt sub-category TZG-000013, under Salt category
 * TZC-000005, under Staples) is stocked; Tea (TZV-000075, under Food) is stocked as the untouched
 * control. Most corruptions cannot be produced through the change API (it refuses deprecating a node
 * with active children, cycles and dangling parents), so they are written directly into the R1
 * snapshot and restored after each scenario. The one legitimate historical case (a vertical
 * deprecated in a later release) goes through the real write path, last.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerReachabilityIT extends AbstractConsumerIT {

    private static final String SNAPSHOTS = "taxonomy_snapshot_nodes";
    private static final String STAPLES = "TZS-000001";
    private static final String FOOD = "TZS-000002";
    private static final String C_SALT = "TZC-000005";
    private static final String G_SALT = "TZG-000013";
    private static final String V_SALT = "TZV-000057";
    private static final String V_TEA = "TZV-000075";
    private static final String V_SLEEP = "TZV-000293";     // deprecated in R2 (real write path)

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_reachability_it");
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
        eligibleProduct("TZP-SALT", V_SALT);
        eligibleProduct("TZP-TEA", V_TEA);
        eligibleProduct("TZP-SLEEP", V_SLEEP);
        assertThat(row(G_SALT).getString("parent_id")).isEqualTo(C_SALT);
        assertThat(row(C_SALT).getString("parent_id")).isEqualTo(STAPLES);
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    // ---------- snapshot fixtures ----------

    private Document row(String nodeId) {
        return db.getCollection(SNAPSHOTS).find(and(eq("release_id", "R1"), eq("node_id", nodeId))).first();
    }

    private void setStatus(String nodeId, String status) {
        db.getCollection(SNAPSHOTS).updateOne(and(eq("release_id", "R1"), eq("node_id", nodeId)),
                new Document("$set", new Document("status", status)));
    }

    private void setParent(String nodeId, String parentId) {
        db.getCollection(SNAPSHOTS).updateOne(and(eq("release_id", "R1"), eq("node_id", nodeId)),
                new Document("$set", new Document("parent_id", parentId)));
    }

    private void insertNode(String nodeId, String type, String parentId, String name) {
        db.getCollection(SNAPSHOTS).insertOne(new Document("release_id", "R1").append("node_id", nodeId)
                .append("node_type", type).append("parent_id", parentId).append("name", name)
                .append("status", "active"));
    }

    private void deleteNodes(String... nodeIds) {
        db.getCollection(SNAPSHOTS).deleteMany(and(eq("release_id", "R1"),
                new Document("node_id", new Document("$in", List.of(nodeIds)))));
    }

    // ---------- helpers ----------

    private ResponseEntity<JsonNode> children(String nodeId, String query) {
        return get("/catalog/v1/categories/" + nodeId + "/children" + query, JsonNode.class);
    }

    private ResponseEntity<JsonNode> list(String nodeId, String query) {
        return get("/catalog/v1/categories/" + nodeId + "/products" + query, JsonNode.class);
    }

    private List<String> rootIds() {
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody().get("items").findValuesAsText("id");
    }

    private double charged(String route) {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", route).summary();
        return s == null ? 0 : s.totalAmount();
    }

    private double outcome(String route, String outcome) {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", route, "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    /**
     * Non-reachable: the ordinary flat 404, charged 1, decided from the snapshot with no product
     * read. The request is a supplier so the before-values are captured HERE, before it runs.
     */
    private void assertNotReachable(String route, java.util.function.Supplier<ResponseEntity<JsonNode>> request) {
        resetCounters();
        double chargedBefore = charged(route);
        ResponseEntity<JsonNode> res = request.get();
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().get("message").asText()).isEqualTo("not found");
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(charged(route) - chargedBefore).as("non-reachable costs 1, like absent").isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).as("no product read before admission, none after a 404").isZero();
    }

    /** Corrupt: flat 503, outcome unavailable, nothing charged, no product read. */
    private void assertCorrupt(String route, java.util.function.Supplier<ResponseEntity<JsonNode>> request) {
        resetCounters();
        double chargedBefore = charged(route);
        double unavailableBefore = outcome(route, "unavailable");
        ResponseEntity<JsonNode> res = request.get();
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(res.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(charged(route) - chargedBefore).isZero();
        assertThat(outcome(route, "unavailable") - unavailableBefore).isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).isZero();
    }

    // ---------- baseline ----------

    @Test @Order(1)
    void the_stocked_vertical_is_reachable_through_its_active_ancestry() {
        assertThat(children(V_SALT, "").getStatusCode().value()).isEqualTo(200);
        assertThat(list(V_SALT, "").getBody().get("items").findValuesAsText("id")).containsExactly("TZP-SALT");
        assertThat(rootIds()).contains(STAPLES, FOOD);
    }

    // ---------- (a) an active child beneath a DEPRECATED parent ----------

    @Test @Order(2)
    void an_active_vertical_beneath_a_deprecated_sub_category_is_not_reachable_by_id() {
        setStatus(G_SALT, "deprecated");
        try {
            assertThat(rootIds()).as("ROOT hides the branch (the consumer-valid walk prunes it)").doesNotContain(STAPLES);

            assertNotReachable("children", () -> children(V_SALT, ""));
            assertNotReachable("list", () -> list(V_SALT, "?page_size=5"));
            assertThat(children(G_SALT, "").getStatusCode().value()).as("the deprecated node itself").isEqualTo(404);

            // the CONTROL branch is untouched
            assertThat(children(V_TEA, "").getStatusCode().value()).isEqualTo(200);
            assertThat(list(V_TEA, "").getStatusCode().value()).isEqualTo(200);
        } finally {
            setStatus(G_SALT, "active");
        }
        assertThat(children(V_SALT, "").getStatusCode().value()).as("restored").isEqualTo(200);
    }

    // ---------- (b) an active child beneath a MERGED grand-parent ----------

    @Test @Order(3)
    void an_active_vertical_beneath_a_merged_category_is_not_reachable_by_id() {
        setStatus(C_SALT, "merged");
        try {
            assertNotReachable("children", () -> children(V_SALT, ""));
            assertNotReachable("children", () -> children(G_SALT, ""));
            assertNotReachable("list", () -> list(V_SALT, ""));
            assertThat(rootIds()).doesNotContain(STAPLES);
        } finally {
            setStatus(C_SALT, "active");
        }
    }

    // ---------- (c) an unattached branch, and the holding verticals ----------

    @Test @Order(4)
    void an_orphan_branch_with_no_super_category_above_it_is_not_reachable() {
        insertNode("X-ORPHAN", "category", null, "Orphan");
        insertNode("X-ORPHAN-G", "sub_category", "X-ORPHAN", "Orphan group");
        insertNode("X-ORPHAN-V", "vertical", "X-ORPHAN-G", "Orphan vertical");
        eligibleProduct("TZP-ORPHAN", "X-ORPHAN-V");
        try {
            assertNotReachable("children", () -> children("X-ORPHAN", ""));
            assertNotReachable("children", () -> children("X-ORPHAN-V", ""));
            assertNotReachable("list", () -> list("X-ORPHAN-V", ""));
            assertThat(rootIds()).as("ROOT never lists a non-super-category root").doesNotContain("X-ORPHAN");
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-ORPHAN"));
            deleteNodes("X-ORPHAN", "X-ORPHAN-G", "X-ORPHAN-V");
        }
    }

    @Test @Order(5)
    void a_stocked_holding_vertical_is_refused_before_any_product_read() {
        eligibleProduct("TZP-HOLD", "TZV-UNCLASSIFIED");
        try {
            assertNotReachable("children", () -> children("TZV-UNCLASSIFIED", ""));
            assertNotReachable("list", () -> list("TZV-SCOPE-BLOCKED", ""));
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-HOLD"));
        }
    }

    // ---------- (d) malformed ancestry ----------

    @Test @Order(6)
    void a_parent_id_that_names_no_row_in_the_release_is_corrupt_and_fails_closed() {
        setParent(G_SALT, "TZC-NOPE");
        try {
            assertCorrupt("children", () -> children(V_SALT, ""));
            assertCorrupt("list", () -> list(V_SALT, ""));
            assertThat(children(V_TEA, "").getStatusCode().value()).as("fault is local to the branch").isEqualTo(200);
        } finally {
            setParent(G_SALT, C_SALT);
        }
    }

    @Test @Order(7)
    void a_row_without_node_id_on_the_path_is_corrupt() {
        // A parent row whose node_id is missing cannot be reached by parent_id at all, so the
        // detectable form on the UPWARD walk is a child whose parent row lacks the id it is named
        // by. The downward walk already refuses such rows (CONSUMER-ERR-2); pin the upward walk by
        // removing the sub-category's node_id and asking for the vertical.
        db.getCollection(SNAPSHOTS).updateOne(and(eq("release_id", "R1"), eq("node_id", G_SALT)),
                new Document("$set", new Document("node_id", "")));
        try {
            assertCorrupt("children", () -> children(V_SALT, ""));
        } finally {
            db.getCollection(SNAPSHOTS).updateOne(and(eq("release_id", "R1"), eq("node_id", "")),
                    new Document("$set", new Document("node_id", G_SALT)));
        }
    }

    // ---------- (e) cycles ----------

    @Test @Order(8)
    void a_cycle_in_the_ancestry_is_corrupt_and_fails_closed() {
        setParent(C_SALT, G_SALT);          // sub-category's parent is the category; category's parent is the sub-category
        try {
            assertCorrupt("children", () -> children(V_SALT, ""));
            assertCorrupt("list", () -> list(V_SALT, ""));
            assertCorrupt("children", () -> children(G_SALT, ""));
        } finally {
            setParent(C_SALT, STAPLES);
        }
    }

    /** TAX-REACH-1a: a PRESENT parent id of a non-string type is corruption, not an orphan. */
    @Test @Order(9)
    void a_numeric_parent_id_on_an_otherwise_active_path_is_corrupt_on_every_route() {
        db.getCollection(SNAPSHOTS).updateOne(and(eq("release_id", "R1"), eq("node_id", G_SALT)),
                new Document("$set", new Document("parent_id", 42)));
        try {
            assertCorrupt("children", () -> children(V_SALT, ""));
            assertCorrupt("list", () -> list(V_SALT, ""));
            // PDP: by its ratified order the weight-1 charge (step 2) and the ONE product read
            // (step 3) precede the reachability check (step 6), so the corruption is detected
            // after them -- 503, outcome unavailable, exactly one product read, charged once.
            resetCounters();
            double pdpCharged = charged("pdp");
            double pdpUnavailable = outcome("pdp", "unavailable");
            ResponseEntity<JsonNode> pdp = get("/catalog/v1/products/TZP-SALT", JsonNode.class);
            assertThat(pdp.getStatusCode().value()).as(String.valueOf(pdp.getBody())).isEqualTo(503);
            assertThat(pdp.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(outcome("pdp", "unavailable") - pdpUnavailable).isEqualTo(1);
            assertThat(charged("pdp") - pdpCharged).as("PDP charges before its first read, by contract").isEqualTo(1);
            assertThat(PRODUCT_FINDS.get()).as("the one point read; nothing projected").isEqualTo(1);
            assertThat(finds("consumer_projection_policy")).isZero();
            assertThat(children(V_TEA, "").getStatusCode().value()).as("fault is local to the branch").isEqualTo(200);
        } finally {
            setParent(G_SALT, C_SALT);
        }
        assertThat(children(V_SALT, "").getStatusCode().value()).as("restored").isEqualTo(200);
    }

    @Test @Order(10)
    void an_ancestry_deeper_than_any_taxonomy_is_corrupt() {
        // 20 chained rows above a stocked vertical, the top one parentless: the bound (16) refuses
        // it before the walk could decide "unattached".
        List<String> ids = new ArrayList<>();
        String parent = null;
        for (int i = 20; i >= 1; i--) {
            String id = "X-DEEP-" + i;
            insertNode(id, "category", parent, "Deep " + i);
            ids.add(id);
            parent = id;
        }
        insertNode("X-DEEP-V", "vertical", parent, "Deep vertical");
        ids.add("X-DEEP-V");
        eligibleProduct("TZP-DEEP", "X-DEEP-V");
        try {
            assertCorrupt("children", () -> children("X-DEEP-V", ""));
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-DEEP"));
            deleteNodes(ids.toArray(new String[0]));
        }
    }

    // ---------- (f) valid historical reachability, through the REAL write path (last) ----------

    @Test @Order(11)
    void a_vertical_deprecated_in_a_later_release_stays_reachable_under_the_release_where_it_was_active() {
        int version = db.getCollection("taxonomy_nodes").find(eq("_id", V_SLEEP)).first().getInteger("version");
        changes.openRelease("R2", "R1");
        changes.deprecateNode(V_SLEEP, version);
        changes.activateRelease("R2");

        assertNotReachable("children", () -> children(V_SLEEP, ""));
        assertThat(children(V_SLEEP, "?release=R1").getStatusCode().value())
                .as("TR-5: reachability is release-bound; the R1 snapshot still has an active path").isEqualTo(200);
        assertThat(list(V_SLEEP, "?release=R1").getBody().get("items").findValuesAsText("id")).containsExactly("TZP-SLEEP");
        assertThat(list(V_SLEEP, "?release=R1").getBody().get("resolved_release_id").asText()).isEqualTo("R1");
        // and the untouched branch is reachable in both releases
        assertThat(children(V_SALT, "").getStatusCode().value()).isEqualTo(200);
        assertThat(children(V_SALT, "?release=R1").getStatusCode().value()).isEqualTo(200);
    }
}
