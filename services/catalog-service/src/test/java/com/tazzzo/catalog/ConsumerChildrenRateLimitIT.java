package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHILD-Q5-1 admission. The IP bucket capacity is set to EXACTLY what one children listing of
 * Staples costs on the seeded tree — 1 + 69 (parent scope) + 69 (the seven child scopes, which
 * partition the same verticals) = 139 — so the first request spends the whole bucket and the
 * second must be refused BEFORE it touches a product.
 *
 * <p>139 is an observation of this tree, not a formula the code contains; it is the same number
 * {@code ConsumerChildrenIT} reads back from the cost summary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerChildrenRateLimitIT extends AbstractConsumerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_children_ratelimit_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "139");
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
    void the_first_request_is_admitted_and_the_second_is_refused_with_a_flat_429_and_zero_probes() {
        ResponseEntity<JsonNode> admitted =
                get("/catalog/v1/categories/TZS-000001/children", JsonNode.class);
        assertThat(admitted.getStatusCode().value())
                .as("capacity 139 admits exactly one children listing of Staples")
                .isEqualTo(200);
        assertThat(admitted.getBody().get("items").findValuesAsText("id")).containsExactly("TZC-000001");

        PRODUCT_FINDS.set(0);
        ResponseEntity<JsonNode> denied =
                get("/catalog/v1/categories/TZS-000001/children", JsonNode.class);

        assertThat(denied.getStatusCode().value()).isEqualTo(429);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(denied.getBody().has("error"))
                .as("flat ERR-1, never the nested CMS envelope").isFalse();
        assertThat(denied.getBody().get("request_id").asText())
                .isEqualTo(denied.getHeaders().getFirst("X-Request-Id"));
        assertThat(Long.parseLong(denied.getHeaders().getFirst("Retry-After")))
                .as("ceil(seconds), minimum 1 — here ~139 units at 1/sec")
                .isBetween(1L, 200L);

        assertThat(PRODUCT_FINDS.get())
                .as("a REFUSED request must do ZERO product work — neither the parent probe nor a "
                        + "child probe; that is why the charge precedes the parent probe")
                .isZero();
    }
}
