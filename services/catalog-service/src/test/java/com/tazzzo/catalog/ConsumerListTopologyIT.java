package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIST is release-bound for REACHABILITY and current for MEMBERSHIP, and a cursor stays on the
 * release that minted it while the current pointer moves underneath (LIST-CURSOR-1).
 *
 * <p>Ordered, because the release state is the fixture: page 1 is taken under R1 as current, THEN
 * R2 is activated (deprecating Sleep Support through the real write path), THEN the R1 cursor is
 * continued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerListTopologyIT extends AbstractConsumerIT {

    private static final String HEALTH = "TZS-000007";
    private static final String G_SLEEP = "TZG-000108";
    private static final String V_SLEEP = "TZV-000293";       // deprecated in R2
    private static final String V_PROTEIN = "TZV-000274";     // stays active

    private String r1Cursor;

    @Autowired MeterRegistry registry;

    private double chargedSoFar() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", "list").summary();
        return s == null ? 0 : s.totalAmount();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_list_topology_it");
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
    void seedR1AndStock() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_SLEEP)).first().getString("name"))
                .isEqualTo("Sleep Support");
        // Health & Wellness: 3 sleep products (ids sort first) and 3 protein products.
        for (int i = 1; i <= 3; i++) {
            eligibleProduct("TZP-A-SLEEP" + i, V_SLEEP);
            eligibleProduct("TZP-B-PROTEIN" + i, V_PROTEIN);
        }
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    private ResponseEntity<JsonNode> list(String nodeId, String query) {
        return get("/catalog/v1/categories/" + nodeId + "/products" + query, JsonNode.class);
    }

    @Test @Order(1)
    void page_one_is_taken_while_R1_is_current() {
        ResponseEntity<JsonNode> res = list(HEALTH, "?page_size=2");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(res.getBody().get("items").findValuesAsText("id")).containsExactly("TZP-A-SLEEP1", "TZP-A-SLEEP2");
        r1Cursor = res.getBody().get("next_cursor").asText();
    }

    @Test @Order(2)
    void the_current_pointer_moves_to_R2_which_deprecates_sleep_support() {
        changes.openRelease("R2", "R1");
        changes.deprecateNode(V_SLEEP, version(V_SLEEP));
        changes.activateRelease("R2");
        assertThat(db.getCollection("system_config").find(eq("_id", "consumer_taxonomy_release")).first()
                .getString("release_id")).isEqualTo("R2");
    }

    @Test @Order(3)
    void the_R1_cursor_continues_on_R1_and_still_reaches_the_sleep_products() {
        ResponseEntity<JsonNode> res = list(HEALTH, "?cursor=" + r1Cursor);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("resolved_release_id").asText())
                .as("the cursor's release wins; 'current' is not re-resolved").isEqualTo("R1");
        assertThat(res.getBody().get("items").findValuesAsText("id"))
                .as("under R1 the sleep vertical is still reachable")
                .containsExactly("TZP-A-SLEEP3", "TZP-B-PROTEIN1");
    }

    @Test @Order(4)
    void a_fresh_request_resolves_R2_where_the_sleep_vertical_is_no_longer_in_scope() {
        ResponseEntity<JsonNode> res = list(HEALTH, "?page_size=10");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("resolved_release_id").asText()).isEqualTo("R2");
        assertThat(res.getBody().get("items").findValuesAsText("id"))
                .as("reachability is release-bound: the deprecated vertical's products are out of scope")
                .containsExactly("TZP-B-PROTEIN1", "TZP-B-PROTEIN2", "TZP-B-PROTEIN3");
    }

    @Test @Order(5)
    void the_deprecated_vertical_itself_is_404_under_R2_and_200_under_R1() {
        double before = chargedSoFar();
        ResponseEntity<JsonNode> current = list(V_SLEEP, "");
        assertThat(current.getStatusCode().value()).isEqualTo(404);
        assertThat(PRODUCT_FINDS.get()).as("non-active in the snapshot: decided without a product read").isZero();
        assertThat(chargedSoFar() - before).as("non-active costs 1, charged before the 404").isEqualTo(1);

        ResponseEntity<JsonNode> past = list(V_SLEEP, "?release=R1");
        assertThat(past.getStatusCode().value()).isEqualTo(200);
        assertThat(past.getBody().get("items").findValuesAsText("id"))
                .containsExactly("TZP-A-SLEEP1", "TZP-A-SLEEP2", "TZP-A-SLEEP3");

        assertThat(list(G_SLEEP, "").getStatusCode().value())
                .as("its sub-category has no consumer-valid vertical in R2: consumer-empty").isEqualTo(404);
    }

    @Test @Order(6)
    void membership_stays_current_on_a_historical_release() {
        db.getCollection("products").updateOne(eq("_id", "TZP-A-SLEEP2"),
                new org.bson.Document("$set", new org.bson.Document("lifecycle", "discontinued")));
        try {
            ResponseEntity<JsonNode> past = list(V_SLEEP, "?release=R1");
            assertThat(past.getBody().get("items").findValuesAsText("id"))
                    .as("R1 scopes the taxonomy; the product set is CURRENT (R-A)")
                    .containsExactly("TZP-A-SLEEP1", "TZP-A-SLEEP3");
        } finally {
            db.getCollection("products").updateOne(eq("_id", "TZP-A-SLEEP2"),
                    new org.bson.Document("$set", new org.bson.Document("lifecycle", "active")));
        }
    }

    @Test @Order(7)
    void an_R1_cursor_presented_with_release_R2_is_INVALID_CURSOR() {
        ResponseEntity<JsonNode> res = list(HEALTH, "?cursor=" + r1Cursor + "&release=R2");
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_CURSOR");
    }
}
