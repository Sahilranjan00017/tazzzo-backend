package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** PDP-1: weight 1, charged BEFORE the first product read. Capacity EXACTLY 1: one served, the next refused with zero reads. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerPdpRateLimitIT extends AbstractConsumerIT {

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_pdp_ratelimit_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> ConsumerListIT.FIXTURE_KEY_B64);
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "1");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "0.01");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "0.01");
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
    void the_first_lookup_is_served_and_the_second_is_refused_with_zero_product_reads() {
        ResponseEntity<JsonNode> ok = get("/catalog/v1/products/TZP-1", JsonNode.class);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);

        resetCounters();
        ResponseEntity<JsonNode> denied = get("/catalog/v1/products/TZP-1", JsonNode.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(429);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(denied.getBody().has("error")).isFalse();
        assertThat(Long.parseLong(denied.getHeaders().getFirst("Retry-After"))).isBetween(1L, 200L);
        assertThat(PRODUCT_FINDS.get()).as("refused BEFORE the first product read").isZero();
        assertThat(finds("taxonomy_snapshot_nodes") + finds("consumer_projection_policy") + finds("attribute_definitions")).isZero();
        assertThat(registry.find(ConsumerObservability.REQUESTS).tags("route", "pdp", "outcome", "rate_limited").counter().count()).isEqualTo(1);
    }
}
