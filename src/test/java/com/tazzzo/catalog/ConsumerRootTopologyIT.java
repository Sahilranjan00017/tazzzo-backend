package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer topology reachability, end to end through REAL change operations. A current, eligible
 * product classified into a vertical that release R2 DEPRECATED must not make its super-category
 * appear — {@code ConsumerEligibility} does not know taxonomy status, so the vertical set handed to
 * it must already be consumer-valid.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerRootTopologyIT extends AbstractConsumerIT {

    /** Health & Wellness → Everyday Wellness → Sleep & Stress → Sleep Support: a one-vertical branch. */
    private static final String HEALTH = "TZS-000007";
    private static final String V_SLEEP = "TZV-000293";
    /** A sibling branch under the same super-category, left active: Protein Powder. */
    private static final String V_PROTEIN = "TZV-000274";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_root_topology_it");
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
        // sanity: the fixture ids are what the seed says they are
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_SLEEP)).first().getString("name"))
                .isEqualTo("Sleep Support");
        changes.openRelease("R2", "R1");
        changes.deprecateNode(V_SLEEP, version(V_SLEEP));
        changes.activateRelease("R2");          // pointer -> R2; snapshot has V_SLEEP deprecated
        eligibleProduct("TZP-SLEEP", V_SLEEP);   // eligible by every product-level rule
    }

    private List<String> rootIds(String query) {
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories" + query, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody().get("items").findValuesAsText("id");
    }

    @Test
    void an_eligible_product_under_a_deprecated_snapshot_vertical_does_NOT_make_root_visible() {
        assertThat(rootIds(""))
                .as("R2 is current; Sleep & Stress is deprecated there; its product must not count")
                .doesNotContain(HEALTH);
    }

    @Test
    void the_same_product_DOES_count_in_the_release_where_the_vertical_was_still_active() {
        assertThat(rootIds("?release=R1"))
                .as("membership is current, but reachability is release-bound (TR-5)")
                .contains(HEALTH);
    }

    @Test
    void an_eligible_product_under_an_ACTIVE_sibling_vertical_makes_it_visible_again() {
        eligibleProduct("TZP-PROTEIN", V_PROTEIN);
        try {
            assertThat(rootIds("")).contains(HEALTH);
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-PROTEIN"));
        }
    }

    /** The Q5 weight is computed from the CONSUMER-VALID set, so a deprecated vertical costs nothing. */
    @Test
    void the_deprecated_vertical_is_excluded_from_the_charged_cost_too() {
        // 293 consumer verticals in R1; one deprecated in R2 -> 292 -> root cost 293 in R2, 294 in R1.
        // Proved indirectly: the reader's consumer walk under HEALTH is one shorter in R2.
        var reader = new com.tazzzo.catalog.schema.SnapshotTaxonomyReader(db);
        assertThat(reader.consumerVerticalIdsInSubtree("R2", HEALTH))
                .hasSize(reader.consumerVerticalIdsInSubtree("R1", HEALTH).size() - 1)
                .doesNotContain(V_SLEEP);
    }
}
