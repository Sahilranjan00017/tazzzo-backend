package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5A — protected ROOT-1 over real HTTP, real Mongo, real Redis.
 *
 * <p>Fixture: the frozen v0.9.0 seed recorded as release R1, then products placed so that exactly
 * TWO super-categories have a current eligible product underneath. Everything else must be hidden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerRootIT extends AbstractConsumerIT {

    private static final String STAPLES = "TZS-000001";     // Basmati lives under here
    private static final String FOOD = "TZS-000002";
    private static final String V_BASMATI = "TZV-000001";   // Staples
    private static final String V_SALT = "TZV-000057";      // Staples
    private static final String V_TEA = "TZV-000075";       // Food

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_consumer_root_it");
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
    void seedAndActivate() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        eligibleProduct("TZP-BAS1", V_BASMATI);   // makes Staples visible
        eligibleProduct("TZP-TEA1", V_TEA);       // makes Food visible
    }

    @BeforeEach
    void resetCounter() {
        PRODUCT_FINDS.set(0);
    }

    private JsonNode root(HttpHeaders headers) {
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories", headers, JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody();
    }

    private List<String> ids(JsonNode body) {
        return body.get("items").findValuesAsText("id");
    }

    // ---------- ROOT shape ----------

    @Test
    void root_returns_only_super_categories_that_have_a_current_eligible_product() {
        JsonNode body = root(new HttpHeaders());

        assertThat(ids(body))
                .as("exactly the two we stocked; the other five are consumer-empty and hidden")
                .containsExactlyInAnyOrder(STAPLES, FOOD);
        assertThat(body.get("resolved_release_id").asText())
                .as("TR-1: the response says which release answered it")
                .isEqualTo("R1");
    }

    @Test
    void parentless_holding_verticals_never_appear() {
        // TZV-UNCLASSIFIED and TZV-SCOPE-BLOCKED are PARENTLESS ROOTS: a "parent_id == null" root
        // would return nine nodes. Even stocked, they must not surface.
        db.getCollection("products").deleteMany(eq("_id", "TZP-HOLD"));
        eligibleProduct("TZP-HOLD", "TZV-UNCLASSIFIED");
        try {
            assertThat(ids(root(new HttpHeaders())))
                    .doesNotContain("TZV-UNCLASSIFIED", "TZV-SCOPE-BLOCKED")
                    .containsExactlyInAnyOrder(STAPLES, FOOD);
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-HOLD"));
        }
    }

    @Test
    void the_item_carries_only_id_and_name() {
        JsonNode item = root(new HttpHeaders()).get("items").get(0);

        assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "name");
        for (String forbidden : new String[]{"status", "parent_id", "version", "node_type",
                "attribute_schema_id", "emoji", "tint", "group", "subcategories", "branch_status"}) {
            assertThat(item.has(forbidden)).as(forbidden + " is not consumer data").isFalse();
        }
    }

    @Test
    void the_envelope_carries_only_the_release_and_the_items() {
        assertThat(root(new HttpHeaders()).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("resolved_release_id", "items");
    }

    @Test
    void ordering_is_the_TR3_transport_key() {
        // "Food" before "Staples" — case-folded name ascending, not seed or id order.
        assertThat(ids(root(new HttpHeaders()))).containsExactly(FOOD, STAPLES);
    }

    // ---------- TR-4A / TR-4B visibility ----------

    @Test
    void exactly_one_existence_probe_per_candidate() {
        PRODUCT_FINDS.set(0);
        root(new HttpHeaders());
        assertThat(PRODUCT_FINDS.get())
                .as("seven consumer-visible super-categories, one probe each")
                .isEqualTo(7);
    }

    @Test
    void a_provisional_draft_or_bundle_product_does_not_make_a_node_visible() {
        product("TZP-PROV", V_SALT, "active", "provisional", "single");
        product("TZP-DRAFT", V_SALT, "draft", "confirmed", "single");
        product("TZP-BUNDLE", null, "active", "confirmed", "bundle");
        try {
            // Staples is visible anyway via Basmati; remove that and only the ineligible remain.
            db.getCollection("products").deleteOne(eq("_id", "TZP-BAS1"));
            assertThat(ids(root(new HttpHeaders())))
                    .as("none of provisional, draft or bundle is a consumer product")
                    .containsExactly(FOOD);
        } finally {
            db.getCollection("products").deleteMany(
                    new Document("_id", new Document("$in",
                            List.of("TZP-PROV", "TZP-DRAFT", "TZP-BUNDLE"))));
            eligibleProduct("TZP-BAS1", V_BASMATI);
        }
    }

    /** REL-MEM-1 / R-A: membership is CURRENT; the classification release is provenance only. */
    @Test
    void a_product_classified_under_another_release_is_still_current_membership() {
        db.getCollection("products").updateOne(eq("_id", "TZP-TEA1"),
                new Document("$set", new Document("classification.release_id", "SOME-OTHER-RELEASE")));
        try {
            assertThat(ids(root(new HttpHeaders())))
                    .as("classification.release_id must NOT remove an otherwise eligible product")
                    .containsExactlyInAnyOrder(STAPLES, FOOD);
        } finally {
            db.getCollection("products").updateOne(eq("_id", "TZP-TEA1"),
                    new Document("$set", new Document("classification.release_id", "R1")));
        }
    }

    // ---------- Q4-a: the bearer header is irrelevant to authority ----------

    @Test
    void anonymous_bogus_reader_and_cms_identities_all_get_the_same_answer() {
        String anonymous = root(new HttpHeaders()).toString();
        for (String token : new String[]{"definitely-not-a-token", "read-test-token", "cms-test-token"}) {
            assertThat(root(bearer(token)).toString())
                    .as("public means the header does not matter, not 'anonymous only'")
                    .isEqualTo(anonymous);
        }
    }

    // ---------- release selection ----------

    @Test
    void an_explicit_active_release_is_usable() {
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories?release=R1", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("resolved_release_id").asText()).isEqualTo("R1");
    }

    @Test
    void an_unknown_release_is_a_flat_404_and_probes_nothing() {
        PRODUCT_FINDS.set(0);
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories?release=NOPE", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(res.getBody().has("error")).as("flat ERR-1, never the nested CMS envelope").isFalse();
        assertThat(res.getBody().get("request_id").isTextual()).isTrue();
        assertThat(PRODUCT_FINDS.get()).isZero();
    }

    @Test
    void the_body_request_id_matches_the_X_Request_Id_header() {
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories?release=NOPE", JsonNode.class);
        assertThat(res.getBody().get("request_id").asText())
                .isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
    }
}
