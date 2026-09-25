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

/**
 * LIST-CURSOR-1: without a usable signing key the LIST route fails CLOSED — 503, before any charge
 * and before any product work — while ROOT and CHILDREN, which need no key, keep serving.
 * The key is deliberately NOT configured in this context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerListKeyMissingIT extends AbstractConsumerIT {

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_list_nokey_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        // NO tazzzo.consumer.cursor-hmac-key-b64 on purpose.
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
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        eligibleProduct("TZP-1", "TZV-000001");
    }

    @Test
    void list_is_503_before_any_charge_or_product_work_and_the_body_names_no_key_detail() {
        resetCounters();
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories/TZV-000001/products", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().get("message").asText()).isEqualTo("service unavailable");
        assertThat(res.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(PRODUCT_FINDS.get()).isZero();
        assertThat(finds("taxonomy_snapshot_nodes")).as("not even the snapshot is read").isZero();
        assertThat(registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", "list").summary())
                .as("nothing was charged").isNull();
        assertThat(registry.find(ConsumerObservability.REQUESTS)
                .tags("route", "list", "outcome", "unavailable").counter().count()).isEqualTo(1);
    }

    @Test
    void root_and_children_do_not_need_the_key() {
        assertThat(get("/catalog/v1/categories", JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(get("/catalog/v1/categories/TZS-000001/children", JsonNode.class).getStatusCode().value())
                .isEqualTo(200);
    }
}
