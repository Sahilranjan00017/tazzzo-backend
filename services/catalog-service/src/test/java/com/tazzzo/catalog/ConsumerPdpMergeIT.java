package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.tx.MergeService;
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

import java.util.List;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDP-MERGE-1: a merged loser resolves INTERNALLY through {@code merged_into} to the eligible,
 * reachable survivor and answers with the survivor's id and body. Real merges go through
 * {@code MergeService} (start + finalizer); the corrupt shapes — which the write path cannot
 * produce — are written directly and restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerPdpMergeIT extends AbstractConsumerIT {

    private static final String V = "TZV-000001";

    @Autowired MergeService merges;
    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_pdp_merge_it");
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
    void seedAndMerge() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        eligibleProduct("TZP-A", V);
        eligibleProduct("TZP-B", V);
        eligibleProduct("TZP-C", V);
        // A -> B, then B -> C: a two-hop chain minted by the REAL write path.
        merges.startMerge("TZP-A", "TZP-B");
        merges.runFinalizer();
        merges.startMerge("TZP-B", "TZP-C");
        merges.runFinalizer();
        assertThat(product("TZP-A").getString("lifecycle")).isEqualTo("merged");
        assertThat(product("TZP-A").getString("merged_into")).isEqualTo("TZP-B");
        assertThat(product("TZP-B").getString("merged_into")).isEqualTo("TZP-C");
        assertThat(product("TZP-C").getString("lifecycle")).isEqualTo("active");
    }

    @BeforeEach
    void reset() {
        resetCounters();
    }

    private Document product(String id) {
        return db.getCollection("products").find(eq("_id", id)).first();
    }

    private ResponseEntity<JsonNode> pdp(String id) {
        return get("/catalog/v1/products/" + id, JsonNode.class);
    }

    private double charged() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", "pdp").summary();
        return s == null ? 0 : s.totalAmount();
    }

    private double unavailable() {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", "pdp", "outcome", "unavailable").counter();
        return c == null ? 0 : c.count();
    }

    /** A merged document written directly, outside the write path (the corrupt shapes). */
    private void mergedRow(String id, Object mergedInto) {
        eligibleProduct(id, V);
        Document set = new Document("lifecycle", "merged");
        if (mergedInto != null) {
            set.append("merged_into", mergedInto);
        }
        db.getCollection("products").updateOne(eq("_id", id), new Document("$set", set));
    }

    private void assertCorrupt(String id, int expectedReads) {
        resetCounters();
        double c = charged();
        double u = unavailable();
        ResponseEntity<JsonNode> res = pdp(id);
        assertThat(res.getStatusCode().value()).as(id + " -> " + res.getBody()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(res.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(res.getHeaders().getFirst("Location")).isNull();
        assertThat(charged() - c).as("charged once, weight 1, before the first read").isEqualTo(1);
        assertThat(unavailable() - u).isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).as(id + ": reads before the corruption was detected").isEqualTo(expectedReads);
    }

    @Test @Order(1)
    void a_merged_loser_answers_with_the_survivor_id_and_body_and_no_redirect() {
        double c = charged();
        ResponseEntity<JsonNode> res = pdp("TZP-A");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst("Location")).as("never an HTTP redirect").isNull();
        assertThat(res.getBody().at("/item/id").asText()).as("the SURVIVOR's id").isEqualTo("TZP-C");
        assertThat(res.getBody().at("/item/title").asText()).isEqualTo("T TZP-C");
        assertThat(res.getBody().toString()).doesNotContain("TZP-A").doesNotContain("TZP-B").doesNotContain("merged");
        assertThat(charged() - c).as("weight 1 for a two-hop chain").isEqualTo(1);
        assertThat(PRODUCT_FINDS.get()).as("read amplification: 1 + 2 hops, recorded for load testing").isEqualTo(3);
        assertThat(pdp("TZP-C").getBody().toString()).as("the survivor's own body is the same body")
                .isEqualTo(res.getBody().toString());
    }

    @Test @Order(2)
    void the_middle_of_the_chain_resolves_too() {
        ResponseEntity<JsonNode> res = pdp("TZP-B");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().at("/item/id").asText()).isEqualTo("TZP-C");
        assertThat(PRODUCT_FINDS.get()).isEqualTo(2);
    }

    @Test @Order(3)
    void a_resolved_survivor_that_is_ineligible_is_the_same_flat_404() {
        db.getCollection("products").updateOne(eq("_id", "TZP-C"),
                new Document("$set", new Document("lifecycle", "discontinued")));
        try {
            ResponseEntity<JsonNode> res = pdp("TZP-A");
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            assertThat(res.getBody().get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(res.getBody().get("message").asText()).isEqualTo("not found");
            assertThat(finds("consumer_projection_policy")).isZero();
        } finally {
            db.getCollection("products").updateOne(eq("_id", "TZP-C"),
                    new Document("$set", new Document("lifecycle", "active")));
        }
    }

    @Test @Order(4)
    void a_resolved_survivor_whose_vertical_is_not_reachable_is_the_same_flat_404() {
        db.getCollection("products").updateOne(eq("_id", "TZP-C"),
                new Document("$set", new Document("classification.vertical_id", "TZV-UNCLASSIFIED")));
        try {
            assertThat(pdp("TZP-A").getStatusCode().value()).isEqualTo(404);
        } finally {
            db.getCollection("products").updateOne(eq("_id", "TZP-C"),
                    new Document("$set", new Document("classification.vertical_id", V)));
        }
    }

    // ---------- corrupt catalogue state: flat 503 ----------

    @Test @Order(5)
    void a_missing_merge_target_is_corrupt() {
        mergedRow("TZP-X-MISSING", "TZP-GONE");
        try {
            assertCorrupt("TZP-X-MISSING", 2);
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-X-MISSING"));
        }
    }

    @Test @Order(6)
    void a_malformed_merge_pointer_is_corrupt() {
        mergedRow("TZP-X-NULLPTR", null);
        try {
            assertCorrupt("TZP-X-NULLPTR", 1);
        } finally {
            db.getCollection("products").deleteOne(eq("_id", "TZP-X-NULLPTR"));
        }
    }

    @Test @Order(7)
    void a_merge_cycle_is_corrupt() {
        mergedRow("TZP-X-CYC1", "TZP-X-CYC2");
        mergedRow("TZP-X-CYC2", "TZP-X-CYC1");
        try {
            assertCorrupt("TZP-X-CYC1", 2);
            assertCorrupt("TZP-X-CYC2", 2);
        } finally {
            db.getCollection("products").deleteMany(new Document("_id", new Document("$in", List.of("TZP-X-CYC1", "TZP-X-CYC2"))));
        }
    }

    @Test @Order(8)
    void a_chain_of_exactly_32_hops_resolves_and_33_is_corrupt() {
        // TZP-H00 -> TZP-H01 -> ... -> TZP-H32 (active). 32 hops from H00.
        for (int i = 0; i < 32; i++) {
            mergedRow(String.format("TZP-H%02d", i), String.format("TZP-H%02d", i + 1));
        }
        eligibleProduct("TZP-H32", V);
        try {
            ResponseEntity<JsonNode> ok = pdp("TZP-H00");
            assertThat(ok.getStatusCode().value()).as("32 hops is within the bound").isEqualTo(200);
            assertThat(ok.getBody().at("/item/id").asText()).isEqualTo("TZP-H32");
            assertThat(PRODUCT_FINDS.get()).isEqualTo(33);

            // one more link in front: 33 hops
            mergedRow("TZP-H-1", "TZP-H00");
            assertCorrupt("TZP-H-1", 33);
        } finally {
            List<String> ids = new java.util.ArrayList<>(List.of("TZP-H-1"));
            for (int i = 0; i <= 32; i++) ids.add(String.format("TZP-H%02d", i));
            db.getCollection("products").deleteMany(new Document("_id", new Document("$in", ids)));
        }
    }
}
