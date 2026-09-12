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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5B — CHILD-1 / CHILD-Q5-1 over real HTTP, real Mongo, real Redis.
 *
 * <p>Fixture: the frozen v0.9.0 seed recorded as release R1, then three eligible products:
 * <pre>
 *   TZP-BAS1   Staples > Rice & Grains > Basmati Rice > Basmati Rice        (TZV-000001)
 *   TZP-SALT1  Staples > Salt, Sugar & Sweeteners > Salt > Salt             (TZV-000057)
 *   TZP-TEA1   Food > Beverages > Tea > Tea                                  (TZV-000075)
 * </pre>
 * So Staples has exactly TWO consumer-visible children out of seven, Rice & Grains exactly ONE of
 * four, and Basmati Rice (sub-category) exactly ONE of three. Everything else is consumer-empty.
 *
 * <p>The charged Q5 cost is read back from the {@code rate_limit.cost} summary — the number the
 * service itself observed — and the product-find counter says how many probes each answer took.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerChildrenIT extends AbstractConsumerIT {

    private static final String STAPLES = "TZS-000001";        // 7 categories, 69 verticals
    private static final String PERSONAL_CARE = "TZS-000003";  // 10 categories, 52 verticals, unstocked
    private static final String C_RICE = "TZC-000001";         // 4 sub-categories, 25 verticals
    private static final String C_ATTA = "TZC-000002";         // unstocked
    private static final String C_SALT = "TZC-000005";
    private static final String G_BASMATI = "TZG-000001";      // 3 verticals
    private static final String V_BASMATI = "TZV-000001";
    private static final String V_SALT = "TZV-000057";
    private static final String V_TEA = "TZV-000075";
    private static final String V_ATTA = "TZV-000026";         // under C_ATTA
    private static final String V_MAIDA = "TZV-000027";        // under C_ATTA

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_consumer_children_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.auth.read-token", () -> "read-test-token");
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
    void seedAndStock() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        // sanity: the fixture ids are what the seed says they are
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_BASMATI)).first().getString("name"))
                .isEqualTo("Basmati Rice");
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_SALT)).first().getString("name"))
                .isEqualTo("Salt");
        assertThat(db.getCollection("taxonomy_nodes").find(eq("_id", V_ATTA)).first().getString("parent_id"))
                .isEqualTo("TZG-000005");
        eligibleProduct("TZP-BAS1", V_BASMATI);
        eligibleProduct("TZP-SALT1", V_SALT);
        eligibleProduct("TZP-TEA1", V_TEA);
    }

    @BeforeEach
    void resetCounter() {
        PRODUCT_FINDS.set(0);
    }

    private ResponseEntity<JsonNode> children(String nodeId, HttpHeaders headers) {
        return get("/catalog/v1/categories/" + nodeId + "/children", headers, JsonNode.class);
    }

    private JsonNode ok(String nodeId) {
        ResponseEntity<JsonNode> res = children(nodeId, new HttpHeaders());
        assertThat(res.getStatusCode().value()).as("children of " + nodeId).isEqualTo(200);
        return res.getBody();
    }

    private List<String> ids(JsonNode body) {
        return body.get("items").findValuesAsText("id");
    }

    /** What the service itself observed as the charged Q5 cost on the children route so far. */
    private double chargedSoFar() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", "children").summary();
        return s == null ? 0 : s.totalAmount();
    }

    // ---------- shape ----------

    @Test
    void a_visible_super_category_lists_only_its_immediate_eligible_children_in_TR3_order() {
        JsonNode body = ok(STAPLES);

        assertThat(ids(body))
                .as("two of seven categories are stocked; TR-3: 'rice…' folds before 'salt…'")
                .containsExactly(C_RICE, C_SALT);
        assertThat(body.get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("resolved_release_id", "items");
    }

    @Test
    void only_the_next_level_appears_never_grandchildren() {
        for (String id : ids(ok(STAPLES))) {
            assertThat(id).as("children of a super-category are categories, nothing deeper").startsWith("TZC-");
        }
        for (String id : ids(ok(C_RICE))) {
            assertThat(id).startsWith("TZG-");
        }
        for (String id : ids(ok(G_BASMATI))) {
            assertThat(id).startsWith("TZV-");
        }
    }

    @Test
    void each_level_hides_its_consumer_empty_children() {
        assertThat(ids(ok(C_RICE))).as("one of four sub-categories is stocked").containsExactly(G_BASMATI);
        assertThat(ids(ok(G_BASMATI))).as("one of three verticals is stocked").containsExactly(V_BASMATI);
    }

    @Test
    void the_item_carries_only_id_and_name() {
        JsonNode item = ok(STAPLES).get("items").get(0);

        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name");
        assertThat(item.get("name").asText()).isEqualTo("Rice & Grains");
        for (String forbidden : new String[]{"status", "parent_id", "version", "node_type",
                "attribute_schema_id", "emoji", "tint", "group", "subcategories", "children",
                "branch_status", "has_children"}) {
            assertThat(item.has(forbidden)).as(forbidden + " is not consumer data").isFalse();
        }
    }

    // ---------- the leaf ----------

    @Test
    void a_visible_vertical_answers_200_with_empty_items_after_exactly_its_own_probe() {
        JsonNode body = ok(V_BASMATI);

        assertThat(body.get("items").isArray()).isTrue();
        assertThat(body.get("items")).as("a vertical has no taxonomy children").isEmpty();
        assertThat(body.get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(PRODUCT_FINDS.get())
                .as("the parent probe proves it is visible; there are no candidates to probe")
                .isEqualTo(1);
    }

    // ---------- CHILD-Q5-1: cost, then parent probe, then one probe per candidate ----------

    @Test
    void the_requested_node_is_probed_once_and_each_active_child_once() {
        ok(STAPLES);
        assertThat(PRODUCT_FINDS.get()).as("1 parent + 7 category candidates").isEqualTo(8);

        PRODUCT_FINDS.set(0);
        ok(C_RICE);
        assertThat(PRODUCT_FINDS.get()).as("1 parent + 4 sub-category candidates").isEqualTo(5);
    }

    @Test
    void the_charged_cost_is_one_plus_the_parent_scope_plus_every_child_scope() {
        double before = chargedSoFar();
        ok(STAPLES);
        assertThat(chargedSoFar() - before)
                .as("1 + 69 (Staples' consumer verticals) + 69 (the same verticals, once per child)")
                .isEqualTo(139);

        before = chargedSoFar();
        ok(V_BASMATI);
        assertThat(chargedSoFar() - before).as("1 + 1 (itself) + 0 (no children)").isEqualTo(2);
    }

    @Test
    void a_missing_node_is_a_flat_404_that_costs_one_and_probes_nothing() {
        double before = chargedSoFar();
        ResponseEntity<JsonNode> res = children("TZC-999999", new HttpHeaders());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().has("error")).as("flat ERR-1, never the nested CMS envelope").isFalse();
        assertThat(res.getBody().get("request_id").asText())
                .isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
        assertThat(chargedSoFar() - before).as("probing the tree for what exists is never free").isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).isZero();
    }

    @Test
    void a_consumer_empty_active_node_is_a_flat_404_after_exactly_one_parent_probe() {
        double before = chargedSoFar();
        ResponseEntity<JsonNode> res = children(PERSONAL_CARE, new HttpHeaders());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(PRODUCT_FINDS.get())
                .as("the parent's own probe missed, so not one of its ten children was probed")
                .isEqualTo(1);
        assertThat(chargedSoFar() - before)
                .as("the full cost was charged BEFORE the parent probe: 1 + 52 + 52")
                .isEqualTo(105);
    }

    @Test
    void absent_and_consumer_empty_are_the_same_answer() {
        JsonNode absent = children("TZC-999999", new HttpHeaders()).getBody();
        JsonNode empty = children(C_ATTA, new HttpHeaders()).getBody();

        assertThat(empty.get("code").asText()).isEqualTo(absent.get("code").asText());
        assertThat(empty.get("message").asText())
                .as("L-5: nothing in the body says whether the node exists")
                .isEqualTo(absent.get("message").asText());
        assertThat(empty.fieldNames()).toIterable()
                .containsExactlyInAnyOrderElementsOf(() -> absent.fieldNames());
    }

    // ---------- the predicate is the same one ----------

    @Test
    void a_child_stocked_only_with_provisional_draft_or_bundle_products_stays_hidden() {
        product("TZP-PROV", V_ATTA, "active", "provisional", "single");
        product("TZP-DRAFT", V_MAIDA, "draft", "confirmed", "single");
        product("TZP-BUNDLE", null, "active", "confirmed", "bundle");
        try {
            assertThat(ids(ok(STAPLES)))
                    .as("none of provisional, draft or bundle is a consumer product")
                    .doesNotContain(C_ATTA)
                    .containsExactly(C_RICE, C_SALT);
        } finally {
            db.getCollection("products").deleteMany(
                    new Document("_id", new Document("$in",
                            List.of("TZP-PROV", "TZP-DRAFT", "TZP-BUNDLE"))));
        }
    }

    @Test
    void a_holding_vertical_is_never_reachable_even_when_stocked() {
        eligibleProduct("TZP-HOLD", "TZV-UNCLASSIFIED");
        try {
            ResponseEntity<JsonNode> res = children("TZV-UNCLASSIFIED", new HttpHeaders());
            assertThat(res.getStatusCode().value())
                    .as("CR-11: a holding vertical is a work queue, not a storefront node")
                    .isEqualTo(404);
            assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-HOLD"));
        }
    }

    /** REL-MEM-1 / R-A: membership is CURRENT; the classification release is provenance only. */
    @Test
    void a_product_classified_under_another_release_is_still_current_membership() {
        db.getCollection("products").updateOne(eq("_id", "TZP-SALT1"),
                new Document("$set", new Document("classification.release_id", "SOME-OTHER-RELEASE")));
        try {
            assertThat(ids(ok(STAPLES))).containsExactly(C_RICE, C_SALT);
        } finally {
            db.getCollection("products").updateOne(eq("_id", "TZP-SALT1"),
                    new Document("$set", new Document("classification.release_id", "R1")));
        }
    }

    // ---------- Q4-a: the bearer header is irrelevant to authority ----------

    @Test
    void anonymous_bogus_reader_and_cms_identities_all_get_the_same_answer() {
        String anonymous = ok(STAPLES).toString();
        for (String token : new String[]{"definitely-not-a-token", "read-test-token", "cms-test-token"}) {
            assertThat(children(STAPLES, bearer(token)).getBody().toString()).isEqualTo(anonymous);
        }
    }

    // ---------- release selection ----------

    @Test
    void an_explicit_active_release_is_usable() {
        ResponseEntity<JsonNode> res =
                get("/catalog/v1/categories/" + STAPLES + "/children?release=R1", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("resolved_release_id").asText()).isEqualTo("R1");
    }

    @Test
    void an_unknown_release_is_a_flat_404_before_any_charge_or_probe() {
        double before = chargedSoFar();
        ResponseEntity<JsonNode> res =
                get("/catalog/v1/categories/" + STAPLES + "/children?release=NOPE", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(chargedSoFar() - before).as("the release is resolved before anything is charged").isZero();
        assertThat(PRODUCT_FINDS.get()).isZero();
    }
}
