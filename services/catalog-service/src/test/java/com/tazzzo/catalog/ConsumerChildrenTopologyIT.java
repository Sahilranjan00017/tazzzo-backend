package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHILD-1 is release-bound (TR-5) and its candidate set is the CONSUMER-VALID one. Same fixture
 * as {@code ConsumerRootTopologyIT}: R1 is the seed; R2 deprecates the vertical Sleep Support
 * through the real write path and becomes current; the vertical is then stocked with a product
 * that is eligible by every product-level rule.
 *
 * <pre>
 *   Health & Wellness (TZS-000007) > Everyday Wellness (TZC-000050) > Sleep & Stress (TZG-000108)
 *       > Sleep Support (TZV-000293)      <- deprecated in R2, the ONLY vertical under TZG-000108
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerChildrenTopologyIT extends AbstractConsumerIT {

    private static final String HEALTH = "TZS-000007";
    private static final String C_WELLNESS = "TZC-000050";
    private static final String G_SLEEP = "TZG-000108";
    private static final String V_SLEEP = "TZV-000293";

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_children_topology_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
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
    void seedAndDeprecateOneVertical() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_SLEEP)).first().getString("name"))
                .isEqualTo("Sleep Support");
        changes.openRelease("R2", "R1");
        changes.deprecateNode(V_SLEEP, version(V_SLEEP));
        changes.activateRelease("R2");          // pointer -> R2; snapshot has V_SLEEP deprecated
        eligibleProduct("TZP-SLEEP", V_SLEEP);
    }

    @BeforeEach
    void resetCounter() {
        PRODUCT_FINDS.set(0);
    }

    private ResponseEntity<JsonNode> children(String nodeId, String query) {
        return get("/catalog/v1/categories/" + nodeId + "/children" + query, JsonNode.class);
    }

    private double chargedSoFar() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", "children").summary();
        return s == null ? 0 : s.totalAmount();
    }

    @Test
    void a_node_that_is_not_active_in_the_current_release_is_404_costs_one_and_probes_nothing() {
        double before = chargedSoFar();
        ResponseEntity<JsonNode> res = children(V_SLEEP, "");

        assertThat(res.getStatusCode().value())
                .as("deprecated in R2 (current): not consumer-reachable, even though stocked")
                .isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().get("resolved_release_id")).isNull();
        assertThat(chargedSoFar() - before).isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).as("non-active is decided from the snapshot alone").isZero();
    }

    @Test
    void the_same_node_is_reachable_in_the_release_where_it_was_still_active() {
        ResponseEntity<JsonNode> res = children(V_SLEEP, "?release=R1");

        assertThat(res.getStatusCode().value())
                .as("membership is current, reachability is release-bound (TR-5)")
                .isEqualTo(200);
        assertThat(res.getBody().get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(res.getBody().get("items")).isEmpty();
        assertThat(PRODUCT_FINDS.get()).isEqualTo(1);
    }

    @Test
    void a_parent_whose_only_consumer_vertical_is_deprecated_is_consumer_empty_in_that_release() {
        // R2: Sleep & Stress has no consumer-valid vertical -> its scope is empty -> the parent
        // probe matches nothing -> 404, without a product read that could find TZP-SLEEP.
        ResponseEntity<JsonNode> current = children(G_SLEEP, "");
        assertThat(current.getStatusCode().value()).isEqualTo(404);
        assertThat(PRODUCT_FINDS.get())
                .as("the parent probe still runs (one), against an empty scope")
                .isEqualTo(1);

        // R1: the same sub-category is visible and lists the vertical.
        PRODUCT_FINDS.set(0);
        ResponseEntity<JsonNode> past = children(G_SLEEP, "?release=R1");
        assertThat(past.getStatusCode().value()).isEqualTo(200);
        assertThat(past.getBody().get("items").findValuesAsText("id")).containsExactly(V_SLEEP);
    }

    @Test
    void a_deprecated_child_is_not_a_candidate_and_is_not_charged_for() {
        // Everyday Wellness has one sub-category, Sleep & Stress, whose one vertical is deprecated
        // in R2. Its child scope is therefore empty and the parent scope is empty too: 1 + 0 + 0.
        double before = chargedSoFar();
        assertThat(children(C_WELLNESS, "").getStatusCode().value()).isEqualTo(404);
        assertThat(chargedSoFar() - before).as("R2: 1 + 0 + 0").isEqualTo(1);

        before = chargedSoFar();
        assertThat(children(C_WELLNESS, "?release=R1").getStatusCode().value()).isEqualTo(200);
        assertThat(chargedSoFar() - before).as("R1: 1 + 1 + 1").isEqualTo(3);
    }

    @Test
    void the_super_category_costs_two_units_less_once_the_vertical_is_gone() {
        double before = chargedSoFar();
        children(HEALTH, "?release=R1");
        double inR1 = chargedSoFar() - before;

        before = chargedSoFar();
        children(HEALTH, "");
        double inR2 = chargedSoFar() - before;

        assertThat(inR1).as("1 + 20 + 20 on the seed").isEqualTo(41);
        assertThat(inR1 - inR2)
                .as("the vertical leaves the parent scope AND its category's child scope")
                .isEqualTo(2);
    }
}
