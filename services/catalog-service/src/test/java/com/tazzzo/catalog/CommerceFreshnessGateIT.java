package com.tazzzo.catalog;

import com.tazzzo.common.audit.TestActors;
import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired io.micrometer.core.instrument.MeterRegistry registry;

    static final String FIXTURE_KEY_B64 = Base64.getEncoder().encodeToString(
            "commerce-gate-fixture-key-32byte!".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_commerce_gate_it");
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
        changes.recordBaseline(TestActors.TEST, "R1");
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

    @Test void the_freshness_gate_records_a_bounded_failure_class_never_a_sku_or_pin() {
        double before = failureClassCount("commerce_list", "freshness_not_ready");
        get("/v1/categories/TZC-000001/products", JsonNode.class);
        assertThat(failureClassCount("commerce_list", "freshness_not_ready") - before)
                .as("PR-10C §7: the freshness-gate rejection is its OWN bounded failure_class")
                .isEqualTo(1.0);

        for (io.micrometer.core.instrument.Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().equals(ConsumerObservability.FAILURE_CLASS)) {
                continue;
            }
            for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
                assertThat(ConsumerObservability.ALLOWED_TAG_KEYS).contains(tag.getKey());
                assertThat(tag.getValue()).doesNotContain("TZC-").doesNotContain("TZP-")
                        .doesNotContain("560").doesNotContain("req_");
            }
        }
    }

    private double failureClassCount(String route, String failureClass) {
        var c = registry.find(ConsumerObservability.FAILURE_CLASS)
                .tags("route", route, "failure_class", failureClass).counter();
        return c == null ? 0 : c.count();
    }
}
