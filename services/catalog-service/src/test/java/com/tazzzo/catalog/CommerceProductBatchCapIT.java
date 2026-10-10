package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.commerce.read.CommerceProductBatchService;
import com.tazzzo.common.audit.TestActors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code tazzzo.commerce.product-batch.max-ids} is honoured: a smaller cap refuses one id over it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceProductBatchCapIT extends AbstractConsumerIT {

    @Autowired CommerceProductBatchService service;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_product_batch_cap_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.commerce.product-batch.max-ids", () -> "5");
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
        changes.recordBaseline(TestActors.TEST, "R1");
        eligibleProduct("TZP-1", "TZV-000001");
    }

    private ResponseEntity<JsonNode> batch(int n) {
        return get("/v1/products:batch?ids=" + IntStream.rangeClosed(1, n).mapToObj(i -> "TZP-" + i)
                .collect(Collectors.joining(",")), JsonNode.class);
    }

    @Test
    void the_configured_cap_is_the_cap() {
        assertThat(service.maxIds()).isEqualTo(5);
        assertThat(batch(5).getStatusCode().value()).isEqualTo(200);
        resetCounters();
        ResponseEntity<JsonNode> over = batch(6);
        assertThat(over.getStatusCode().value()).isEqualTo(400);
        assertThat(over.getBody().get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(PRODUCT_FINDS.get()).isZero();
    }
}
