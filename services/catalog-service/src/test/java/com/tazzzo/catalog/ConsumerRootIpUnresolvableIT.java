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
 * Q5-IP-2a. Loopback is configured as a TRUSTED proxy here, so the test client arrives as a
 * trusted peer with no {@code X-Forwarded-For} — an unresolvable client identity.
 *
 * <p>It fails closed. Falling back to the proxy's own address would put every client behind it in
 * ONE bucket, so the limiter would either throttle the entire customer base at once or be
 * effectively disabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumerRootIpUnresolvableIT extends AbstractConsumerIT {

    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_root_ipunresolvable_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "127.0.0.0/8,::1/128");
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
    void a_trusted_peer_with_no_forwarded_header_fails_closed() {
        PRODUCT_FINDS.set(0);
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(PRODUCT_FINDS.get()).isZero();

        // Q5-OBS-1: an unresolvable identity is an UNAVAILABLE outcome with a REAL latency, timed
        // at the same controller boundary as every other outcome -- never a placeholder.
        var counter = registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.REQUESTS)
                .tags("route", "root", "outcome", "unavailable").counter();
        var timer = registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.REQUEST_DURATION)
                .tags("route", "root", "outcome", "unavailable").timer();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1);
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .as("a measured elapsed duration, not Duration.ZERO")
                .isGreaterThan(0);
        double allOutcomes = 0;
        for (String o : new String[]{"success", "not_found", "rate_limited", "unavailable"}) {
            var c = registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.REQUESTS)
                    .tags("route", "root", "outcome", o).counter();
            allOutcomes += c == null ? 0 : c.count();
        }
        assertThat(allOutcomes).as("exactly ONE request observation for one request").isEqualTo(1);
    }

    /** PDP-1 / PDP-OBS-1: an unresolvable identity is ONE measured unavailable outcome, zero product reads. */
    @Test
    void pdp_with_an_unresolvable_client_identity_fails_closed_and_is_measured_once() {
        resetCounters();
        ResponseEntity<JsonNode> res = get("/catalog/v1/products/TZP-1", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(PRODUCT_FINDS.get()).isZero();
        var timer = registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.REQUEST_DURATION)
                .tags("route", "pdp", "outcome", "unavailable").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0);
        double allOutcomes = 0;
        for (var c : registry.find(com.tazzzo.catalog.consumer.ConsumerObservability.REQUESTS).tags("route", "pdp").counters()) {
            allOutcomes += c.count();
        }
        assertThat(allOutcomes).as("exactly ONE observation for one PDP request").isEqualTo(1);
    }

    @Test
    void the_same_trusted_peer_WITH_a_forwarded_client_resolves_and_serves() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", "198.51.100.7");
        assertThat(get("/catalog/v1/categories", headers, JsonNode.class).getStatusCode().value())
                .as("the resolver works; it is the MISSING header that fails closed")
                .isEqualTo(200);
    }
}
