package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5D.1 — PDP-1 over real HTTP, real Mongo, real Redis, with the 2026-09-17 rulings
 * (PDP-SHAPE-1 envelope A, PDP-PATH-1, TAX-REACH-1, PDP-OBS-1, PDP-CTRL-1).
 *
 * <p>Fixture: the seed as R1; under Basmati Rice (TZV-000001, policy v3): TZP-OK (single), TZP-VP
 * (variant pack), TZP-REL (classified under another release), and every excluded state; one
 * product under Sona Masoori (TZV-000004, policy v7). The find-counting listener observes every
 * products read and its projection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPdpIT extends AbstractConsumerIT {

    private static final String V_BASMATI = "TZV-000001";
    private static final String V_SONA = "TZV-000004";
    private static final String C_RICE = "TZC-000001";

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_consumer_pdp_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> ConsumerListIT.FIXTURE_KEY_B64);
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
        eligibleProduct("TZP-OK", V_BASMATI);
        db.getCollection("products").updateOne(eq("_id", "TZP-OK"),
                new Document("$set", new Document("attributes", new Document("aged", true).append("grain_length", "long"))));
        product("TZP-VP", V_BASMATI, "active", "confirmed", "variant_pack");
        eligibleProduct("TZP-REL", V_BASMATI);
        db.getCollection("products").updateOne(eq("_id", "TZP-REL"),
                new Document("$set", new Document("classification.release_id", "SOME-OTHER-RELEASE")));
        product("TZP-DRAFT", V_BASMATI, "draft", "confirmed", "single");
        product("TZP-DISC", V_BASMATI, "discontinued", "confirmed", "single");
        product("TZP-ARCH", V_BASMATI, "archived", "confirmed", "single");
        product("TZP-MERGING", V_BASMATI, "merging", "confirmed", "single");
        product("TZP-PROV", V_BASMATI, "active", "provisional", "single");
        product("TZP-REVIEW", V_BASMATI, "active", "review", "single");
        product("TZP-BLOCKED", V_BASMATI, "active", "scope_blocked", "single");
        product("TZP-BUNDLE", null, "active", "confirmed", "bundle");
        eligibleProduct("TZP-HOLD", "TZV-UNCLASSIFIED");
        eligibleProduct("TZP-CAT", C_RICE);                       // classified to a CATEGORY id
        eligibleProduct("TZP-NOVERT", "TZV-999999");              // vertical absent from the release
        eligibleProduct("TZP-SONA", V_SONA);
        policy(V_BASMATI, "v3", "aged:1:Aged", "grain_length:2:Grain length");
        policy(V_SONA, "v7", "aged:1:Aged");
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    private void policy(String vertical, String version, String... entries) {
        List<Document> attrs = new ArrayList<>();
        for (String e : entries) {
            String[] parts = e.split(":");
            attrs.add(new Document("attribute_key", parts[0])
                    .append("display_order", Integer.parseInt(parts[1]))
                    .append("display_label", parts[2]));
        }
        db.getCollection("consumer_projection_policy").insertOne(
                new Document("vertical_id", vertical).append("projection_version", version)
                        .append("attributes", attrs));
    }

    private ResponseEntity<JsonNode> pdp(String productId, String query) {
        return get("/catalog/v1/products/" + productId + query, JsonNode.class);
    }

    private double chargedSoFar() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", "pdp").summary();
        return s == null ? 0 : s.totalAmount();
    }

    private double requests(String outcome) {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", "pdp", "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private static void assertFlat(ResponseEntity<JsonNode> res, int status, String code) {
        assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(status);
        assertThat(res.getBody().get("code").asText()).isEqualTo(code);
        assertThat(res.getBody().has("error")).as("flat ERR-1").isFalse();
        assertThat(res.getBody().get("request_id").asText()).isEqualTo(res.getHeaders().getFirst("X-Request-Id"));
        assertThat(res.getBody().fieldNames()).toIterable().containsExactlyInAnyOrder("code", "message", "request_id");
    }

    /** The SAME 404 for every hidden reason: body identical apart from request_id, cost 1, no probe. */
    private void assertSame404(String productId, int expectedProductFinds) {
        resetCounters();
        double before = chargedSoFar();
        ResponseEntity<JsonNode> res = pdp(productId, "");
        assertFlat(res, 404, "NOT_FOUND");
        assertThat(res.getBody().get("message").asText()).isEqualTo("not found");
        assertThat(chargedSoFar() - before).as(productId + ": cost 1, charged before the read").isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).as(productId + ": product reads").isEqualTo(expectedProductFinds);
        assertThat(finds("consumer_projection_policy") + finds("attribute_definitions"))
                .as(productId + ": nothing projected").isZero();
    }

    // ---------- the happy path and the exact shape ----------

    @Test
    void an_eligible_reachable_product_is_returned_in_envelope_A_with_exactly_the_ratified_fields() {
        ResponseEntity<JsonNode> res = pdp("TZP-OK", "");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = res.getBody();

        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("resolved_release_id", "item");
        assertThat(body.get("resolved_release_id").asText()).isEqualTo("R1");
        JsonNode item = body.get("item");
        assertThat(item.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "title", "brandCode", "attributes", "projectionVersion");
        assertThat(item.get("id").asText()).isEqualTo("TZP-OK");
        assertThat(item.get("title").asText()).isEqualTo("T TZP-OK");
        assertThat(item.get("brandCode").asText()).isEqualTo("BR");
        assertThat(item.get("projectionVersion").asText()).isEqualTo("v3");
        assertThat(item.get("attributes").findValuesAsText("key")).containsExactly("aged");   // grain_length: [] vocabulary in seed
        for (String forbidden : new String[]{"lifecycle", "version", "classification", "canonical_key",
                "attribute_schema_id", "identity", "attributes_meta", "created_at", "product_type",
                "merged_into", "taxonomyPath", "gtins", "price", "offers_current", "status", "release_id", "_id"}) {
            assertThat(item.has(forbidden)).as("item." + forbidden).isFalse();
            assertThat(body.has(forbidden)).as("envelope." + forbidden).isFalse();
        }
    }

    @Test
    void the_read_plan_is_one_narrow_point_read_and_no_probe() {
        pdp("TZP-OK", "");

        assertThat(PRODUCT_FINDS.get()).isEqualTo(1);
        Document read = PRODUCT_FIND_COMMANDS.get(0);
        assertThat(read.getList("projection", String.class)).containsExactlyInAnyOrder(
                "_id", "title", "brand_code", "product_type", "lifecycle",
                "classification.status", "classification.vertical_id", "attributes", "merged_into");
        assertThat(read.getBoolean("has_skip")).isFalse();
        assertThat(finds("taxonomy_snapshot_nodes")).as("vertical + 3 ancestors").isBetween(1, 4);
        assertThat(finds("consumer_projection_policy")).isEqualTo(1);
        assertThat(finds("attribute_definitions")).isEqualTo(1);
        assertThat(registry.find(ConsumerObservability.PROBE_DURATION).tags("route", "pdp").timers())
                .as("PDP issues no existence probe").isEmpty();
    }

    @Test
    void a_variant_pack_and_a_product_classified_under_another_release_are_both_served() {
        assertThat(pdp("TZP-VP", "").getStatusCode().value()).isEqualTo(200);
        assertThat(pdp("TZP-VP", "").getBody().at("/item/id").asText()).isEqualTo("TZP-VP");
        assertThat(pdp("TZP-REL", "").getStatusCode().value()).as("R-A: provenance, not membership").isEqualTo(200);
    }

    @Test
    void item_local_projection_version_follows_the_product_vertical() {
        assertThat(pdp("TZP-SONA", "").getBody().at("/item/projectionVersion").asText()).isEqualTo("v7");
        assertThat(pdp("TZP-OK", "").getBody().at("/item/projectionVersion").asText()).isEqualTo("v3");
    }

    @Test
    void no_policy_is_an_empty_list_with_a_null_version_and_a_malformed_policy_is_a_flat_503() {
        db.getCollection("consumer_projection_policy").deleteOne(eq("vertical_id", V_SONA));
        try {
            JsonNode item = pdp("TZP-SONA", "").getBody().get("item");
            assertThat(item.get("attributes")).isEmpty();
            assertThat(item.get("projectionVersion").isNull()).as("null, never a synthesized 0").isTrue();

            db.getCollection("consumer_projection_policy").insertOne(new Document("vertical_id", V_SONA)
                    .append("projection_version", "  ").append("attributes", List.of()));
            ResponseEntity<JsonNode> res = pdp("TZP-SONA", "");
            assertFlat(res, 503, "SERVICE_UNAVAILABLE");
            assertThat(res.getHeaders().getFirst("Retry-After")).isNull();
        } finally {
            db.getCollection("consumer_projection_policy").deleteOne(eq("vertical_id", V_SONA));
            policy(V_SONA, "v7", "aged:1:Aged");
        }
    }

    // ---------- the SAME 404 for every hidden reason ----------

    @Test
    void every_excluded_state_is_the_same_flat_404_with_cost_one() {
        assertSame404("TZP-NOPE", 1);         // unknown
        assertSame404("TZP-DRAFT", 1);        // lifecycle draft
        assertSame404("TZP-DISC", 1);         // discontinued
        assertSame404("TZP-ARCH", 1);         // archived
        assertSame404("TZP-MERGING", 1);      // mid-merge
        assertSame404("TZP-PROV", 1);         // classification provisional
        assertSame404("TZP-REVIEW", 1);       // classification review
        assertSame404("TZP-BLOCKED", 1);      // classification scope_blocked
        assertSame404("TZP-BUNDLE", 1);       // LIST-ELIG-1
        assertSame404("TZP-HOLD", 1);         // holding vertical (predicate AND reachability)
        assertSame404("TZP-CAT", 1);          // classified to a category id: not a vertical
        assertSame404("TZP-NOVERT", 1);       // vertical absent from the release
    }

    @Test
    void the_404_bodies_are_indistinguishable() {
        JsonNode unknown = pdp("TZP-NOPE", "").getBody();
        for (String id : new String[]{"TZP-DRAFT", "TZP-PROV", "TZP-BUNDLE", "TZP-HOLD", "TZP-CAT", "TZP-NOVERT"}) {
            JsonNode other = pdp(id, "").getBody();
            assertThat(other.get("code").asText()).isEqualTo(unknown.get("code").asText());
            assertThat(other.get("message").asText()).isEqualTo(unknown.get("message").asText());
            assertThat(other.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(() -> unknown.fieldNames());
        }
    }

    @Test
    void an_unknown_release_is_a_404_before_any_charge_and_a_blank_release_is_current() {
        double before = chargedSoFar();
        assertFlat(pdp("TZP-OK", "?release=NOPE"), 404, "NOT_FOUND");
        assertThat(chargedSoFar() - before).isZero();
        assertThat(PRODUCT_FINDS.get()).isZero();
        assertThat(pdp("TZP-OK", "?release=").getBody().get("resolved_release_id").asText()).isEqualTo("R1");
        assertThat(pdp("TZP-OK", "?release=R1").getStatusCode().value()).isEqualTo(200);
    }

    // ---------- Q4-a ----------

    @Test
    void the_bearer_header_is_irrelevant_to_the_answer() {
        String anonymous = pdp("TZP-OK", "").getBody().toString();
        for (String token : new String[]{"definitely-not-a-token", "cms-test-token"}) {
            assertThat(get("/catalog/v1/products/TZP-OK", bearer(token), JsonNode.class).getBody().toString())
                    .isEqualTo(anonymous);
        }
    }

    // ---------- PDP-OBS-1 ----------

    @Test
    void every_pdp_request_records_exactly_one_outcome_with_bounded_tags_and_no_framework_http_meters() {
        double success = requests("success");
        double notFound = requests("not_found");
        double all = 0;
        for (var c : registry.find(ConsumerObservability.REQUESTS).tags("route", "pdp").counters()) all += c.count();

        pdp("TZP-OK", "");
        pdp("TZP-VP", "");
        pdp("TZP-NOPE", "");
        pdp("TZP-DRAFT", "");
        pdp("TZP-OK", "?release=NOPE");

        double allAfter = 0;
        for (var c : registry.find(ConsumerObservability.REQUESTS).tags("route", "pdp").counters()) allAfter += c.count();
        assertThat(allAfter - all).as("five requests, five observations").isEqualTo(5);
        assertThat(requests("success") - success).isEqualTo(2);
        assertThat(requests("not_found") - notFound).isEqualTo(3);
        assertThat(registry.find(ConsumerObservability.REQUEST_DURATION).tags("route", "pdp", "outcome", "success").timer()
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0);

        List<String> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (name.startsWith("http.server.")) {
                violations.add("framework meter " + name);
            }
            if (!name.startsWith("tazzzo.catalog.consumer.")) continue;
            for (Tag tag : meter.getId().getTags()) {
                if (!ConsumerObservability.ALLOWED_TAG_KEYS.contains(tag.getKey()) || !tag.getValue().matches("[a-z_]{1,20}")) {
                    violations.add(name + " " + tag.getKey() + "=" + tag.getValue());
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
