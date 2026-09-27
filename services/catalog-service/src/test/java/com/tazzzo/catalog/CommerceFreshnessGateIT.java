package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10B §31 — the category-products list requires a production-managed projection. With
 * {@code tazzzo.freshness.enabled} OFF (the default) the list endpoint fails closed with 503 rather
 * than serve unmanaged projection rows; categories/children are not gated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceFreshnessGateIT extends AbstractConsumerIT {

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "commerce-gate-fixture-key-32byte!".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_commerce_gate_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> FIXTURE_KEY_B64);
        // tazzzo.freshness.enabled deliberately UNSET -> defaults false -> list fails closed
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
    }

    @Test void list_fails_closed_503_when_freshness_disabled() {
        ResponseEntity<JsonNode> res = get("/v1/categories/TZC-000001/products", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().at("/code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(res.getBody().at("/retryable").asBoolean()).isTrue();
    }

    @Test void categories_still_served_when_freshness_disabled() {
        // categories/children are NOT gated on projection freshness
        assertThat(get("/v1/categories", JsonNode.class).getStatusCode().value()).isEqualTo(200);
    }
}
