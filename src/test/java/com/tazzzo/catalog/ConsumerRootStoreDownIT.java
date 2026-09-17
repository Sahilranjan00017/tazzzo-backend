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
 * Q5-FAIL-1: the limiter store is unreachable. The consumer surface FAILS CLOSED with a retryable
 * 503 — it never fails open, never degrades to per-instance limiting, and never reports an
 * infrastructure fault as \"you exceeded your rate\".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerRootStoreDownIT extends AbstractConsumerIT {

    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_root_storedown_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://127.0.0.1:6");
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "1000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "1000");
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
    void an_unreachable_limiter_store_is_a_flat_503_and_probes_nothing() {
        PRODUCT_FINDS.set(0);
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(PRODUCT_FINDS.get())
                .as("no admission decision means no product work happens at all").isZero();
        assertThat(registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.REQUESTS)
                .tags("route", "root", "outcome", "unavailable").counter().count())
                .as("a limiter failure is an UNAVAILABLE outcome, never rate_limited").isEqualTo(1);
        assertThat(registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.RATE_LIMIT_REMAINING).summary())
                .as("no fabricated remaining for a decision that was never made").isNull();
    }

    /** PDP-1: the same fail-closed answer, and the product is never read. */
    @Test
    void pdp_under_an_unreachable_limiter_store_is_a_flat_503_with_zero_product_reads() {
        resetCounters();
        ResponseEntity<JsonNode> res = get("/catalog/v1/products/TZP-1", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().has("error")).isFalse();
        assertThat(PRODUCT_FINDS.get()).isZero();
        assertThat(registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.REQUESTS)
                .tags("route", "pdp", "outcome", "unavailable").counter().count()).isEqualTo(1);
    }
}
