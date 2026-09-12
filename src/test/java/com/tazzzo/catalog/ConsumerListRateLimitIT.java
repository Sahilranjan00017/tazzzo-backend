package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q5-f on LIST. The IP bucket capacity is set to EXACTLY {@code 1 + 20} — the ratified weight of
 * one default-size page — so the first request spends the whole bucket and the second is refused
 * BEFORE the scope probe and before the page read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerListRateLimitIT extends AbstractConsumerIT {

    @Autowired MeterRegistry registry;

    private double outcome(String outcome) {
        var c = registry.find(ConsumerObservability.REQUESTS).tags("route", "list", "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_list_ratelimit_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> ConsumerListIT.FIXTURE_KEY_B64);
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "21");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "1");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "1");
    }

    @BeforeAll
    void seed() {
        flushRateLimitBuckets();   // this suite pins capacity to an exact cost: start from an empty store
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        eligibleProduct("TZP-1", "TZV-000001");
    }

    @Test
    void the_first_page_is_admitted_and_the_second_is_refused_with_a_flat_429_and_zero_product_finds() {
        ResponseEntity<JsonNode> admitted = get("/catalog/v1/categories/TZV-000001/products", JsonNode.class);
        assertThat(admitted.getStatusCode().value()).as("capacity 21 admits exactly one default page").isEqualTo(200);
        assertThat(admitted.getBody().get("items").findValuesAsText("id")).containsExactly("TZP-1");

        resetCounters();
        ResponseEntity<JsonNode> denied = get("/catalog/v1/categories/TZV-000001/products", JsonNode.class);

        assertThat(denied.getStatusCode().value()).isEqualTo(429);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(denied.getBody().has("error")).isFalse();
        assertThat(denied.getBody().get("request_id").asText())
                .isEqualTo(denied.getHeaders().getFirst("X-Request-Id"));
        assertThat(Long.parseLong(denied.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        assertThat(PRODUCT_FINDS.get())
                .as("a REFUSED request does ZERO product work: no scope probe, no page read")
                .isZero();
        assertThat(finds("consumer_projection_policy") + finds("attribute_definitions"))
                .as("and no projection reads either").isZero();
        assertThat(outcome("rate_limited")).as("Q5-OBS-1b: the refusal is its own bounded outcome").isEqualTo(1);
        assertThat(outcome("success")).isEqualTo(1);
    }
}
