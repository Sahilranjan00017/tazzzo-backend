package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDP reachability is release-bound (TR-5, TAX-REACH-1) and membership is current (R-A). R2 is
 * produced through the REAL write path: Sleep Support deprecated, and Protein Powder merged into
 * Protein Bars — a product left classified under the merged vertical is "awaiting reclassification"
 * and must not open until it is reclassified. Corrupt ancestry is written directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerPdpTopologyIT extends AbstractConsumerIT {

    private static final String V_SLEEP = "TZV-000293";      // deprecated in R2
    private static final String V_PROTEIN = "TZV-000274";    // merged in R2 into V_BARS
    private static final String V_BARS = "TZV-000275";
    private static final String V_SALT = "TZV-000057";       // stays active; its ancestry is corrupted directly
    private static final String G_SALT = "TZG-000013";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_pdp_topology_it");
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

    private int version(String nodeId) {
        return db.getCollection("taxonomy_nodes").find(eq("_id", nodeId)).first().getInteger("version");
    }

    @BeforeAll
    void seedR1ThenR2() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_PROTEIN)).first().getString("parent_id"))
                .isEqualTo(db.getCollection("taxonomy_nodes").find(eq("_id", V_BARS)).first().getString("parent_id"));
        eligibleProduct("TZP-SLEEP", V_SLEEP);
        eligibleProduct("TZP-PROTEIN", V_PROTEIN);
        eligibleProduct("TZP-SALT", V_SALT);

        changes.openRelease("R2", "R1");
        changes.deprecateNode(V_SLEEP, version(V_SLEEP));
        changes.mergeNodes(V_PROTEIN, version(V_PROTEIN), V_BARS, true);
        changes.activateRelease("R2");
        assertThat(db.getCollection("taxonomy_snapshot_nodes")
                .find(and(eq("release_id", "R2"), eq("node_id", V_PROTEIN))).first().getString("status")).isEqualTo("merged");
        assertThat(db.getCollection("products").find(eq("_id", "TZP-PROTEIN")).first()
                .get("classification", Document.class).getString("vertical_id"))
                .as("the merge queues reclassification; the product still points at the loser").isEqualTo(V_PROTEIN);
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    private ResponseEntity<JsonNode> pdp(String id, String query) {
        return get("/catalog/v1/products/" + id + query, JsonNode.class);
    }

    @Test @Order(1)
    void a_product_under_a_vertical_deprecated_in_the_current_release_is_404_now_and_200_under_R1() {
        assertThat(pdp("TZP-SLEEP", "").getStatusCode().value()).isEqualTo(404);
        ResponseEntity<JsonNode> past = pdp("TZP-SLEEP", "?release=R1");
        assertThat(past.getStatusCode().value()).isEqualTo(200);
        assertThat(past.getBody().get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(past.getBody().at("/item/id").asText()).isEqualTo("TZP-SLEEP");
    }

    @Test @Order(2)
    void a_product_awaiting_reclassification_after_a_taxonomy_merge_is_404_now_and_200_under_R1() {
        // PDP follows the PRODUCT's merged_into, never the NODE's: the loser vertical is simply
        // non-active in R2, so the product is unreachable until the queued reclassification lands.
        assertThat(pdp("TZP-PROTEIN", "").getStatusCode().value()).isEqualTo(404);
        assertThat(pdp("TZP-PROTEIN", "?release=R1").getStatusCode().value()).isEqualTo(200);
        assertThat(PRODUCT_FINDS.get()).as("one read per request, no node-merge following").isEqualTo(2);
    }

    @Test @Order(3)
    void membership_is_current_even_on_a_historical_release() {
        db.getCollection("products").updateOne(eq("_id", "TZP-SLEEP"),
                new Document("$set", new Document("lifecycle", "discontinued")));
        try {
            assertThat(pdp("TZP-SLEEP", "?release=R1").getStatusCode().value())
                    .as("R1 scopes the taxonomy; the product's state is CURRENT").isEqualTo(404);
        } finally {
            db.getCollection("products").updateOne(eq("_id", "TZP-SLEEP"),
                    new Document("$set", new Document("lifecycle", "active")));
        }
    }

    /** TAX-REACH-1 on PDP: the vertical's own row is active, its parent is not -> hidden, 404. */
    @Test @Order(4)
    void a_product_under_an_active_vertical_beneath_a_deprecated_parent_is_404() {
        db.getCollection("taxonomy_snapshot_nodes").updateOne(and(eq("release_id", "R2"), eq("node_id", G_SALT)),
                new Document("$set", new Document("status", "deprecated")));
        try {
            ResponseEntity<JsonNode> res = pdp("TZP-SALT", "");
            assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(404);
            assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(finds("consumer_projection_policy")).as("nothing projected for a hidden product").isZero();
            assertThat(pdp("TZP-SALT", "?release=R1").getStatusCode().value()).as("R1's ancestry is intact").isEqualTo(200);
        } finally {
            db.getCollection("taxonomy_snapshot_nodes").updateOne(and(eq("release_id", "R2"), eq("node_id", G_SALT)),
                    new Document("$set", new Document("status", "active")));
        }
    }

    @Test @Order(5)
    void corrupt_ancestry_of_the_product_vertical_is_a_flat_503_in_that_release_only() {
        db.getCollection("taxonomy_snapshot_nodes").updateOne(and(eq("release_id", "R2"), eq("node_id", G_SALT)),
                new Document("$set", new Document("parent_id", "TZC-NOPE")));
        try {
            ResponseEntity<JsonNode> res = pdp("TZP-SALT", "");
            assertThat(res.getStatusCode().value()).isEqualTo(503);
            assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(pdp("TZP-SALT", "?release=R1").getStatusCode().value()).as("R1's snapshot is intact").isEqualTo(200);
        } finally {
            db.getCollection("taxonomy_snapshot_nodes").updateOne(and(eq("release_id", "R2"), eq("node_id", G_SALT)),
                    new Document("$set", new Document("parent_id", "TZC-000005")));
        }
        assertThat(pdp("TZP-SALT", "").getStatusCode().value()).isEqualTo(200);
    }
}
