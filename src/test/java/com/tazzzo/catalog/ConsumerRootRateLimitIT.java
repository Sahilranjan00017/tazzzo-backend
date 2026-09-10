package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q5 admission on ROOT-1. The IP bucket capacity is set to EXACTLY the cost one root listing
 * computes on the seeded tree (1 + 293 descendant verticals = 294), so the first request spends the
 * whole bucket and the second must be refused.
 *
 * <p>Together with {@code ConsumerRootCostBoundIT}, which sets capacity to 293 and gets an
 * impossible-cost refusal, this pins the charged weight at exactly 294 WITHOUT the code containing
 * that number anywhere: 294 is an observation of this tree, not the formula.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerRootRateLimitIT extends AbstractConsumerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_root_ratelimit_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "294");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "1");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "1");
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        eligibleProduct("TZP-1", "TZV-000001");
    }

    @Test
    void the_first_request_is_admitted_and_the_second_is_refused_with_a_flat_429() {
        assertThat(get("/catalog/v1/categories", JsonNode.class).getStatusCode().value())
                .as("capacity 294 admits exactly one root listing")
                .isEqualTo(200);

        PRODUCT_FINDS.set(0);
        ResponseEntity<JsonNode> denied = get("/catalog/v1/categories", JsonNode.class);

        assertThat(denied.getStatusCode().value()).isEqualTo(429);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(denied.getBody().has("error"))
                .as("flat ERR-1, never the nested CMS envelope").isFalse();
        assertThat(denied.getBody().get("request_id").asText())
                .isEqualTo(denied.getHeaders().getFirst("X-Request-Id"));

        long retryAfter = Long.parseLong(denied.getHeaders().getFirst("Retry-After"));
        assertThat(retryAfter)
                .as("ceil(seconds), minimum 1 — here ~294 units at 1/sec")
                .isBetween(1L, 400L);

        assertThat(PRODUCT_FINDS.get())
                .as("a REFUSED request must do ZERO product work — that is the whole point of "
                        + "charging before the probes")
                .isZero();
    }
}
