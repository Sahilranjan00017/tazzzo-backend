package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.location.GeoPincodeResolver;
import com.tazzzo.location.GeoPoint;
import com.tazzzo.location.GeoProviderUnavailableException;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** lat/lng on /v1/serviceability with a geo provider ENABLED (a deterministic fake; no external provider ships). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class, GeoServiceabilityIT.FakeGeo.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeoServiceabilityIT extends AbstractConsumerIT {

    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile boolean outage;

    @TestConfiguration
    static class FakeGeo {
        @Bean
        @Primary
        GeoPincodeResolver fakeGeo() {
            return new GeoPincodeResolver() {
                @Override public boolean enabled() { return true; }
                @Override public Optional<String> resolve(GeoPoint p) {
                    CALLS.incrementAndGet();
                    if (outage) throw new GeoProviderUnavailableException();
                    if (p.lat() == 12.9 && p.lng() == 77.6) return Optional.of("560001");
                    if (p.lat() == 1.0) return Optional.of("garbage-from-provider");
                    return Optional.empty();
                }
            };
        }
    }

    @org.springframework.beans.factory.annotation.Autowired com.mongodb.client.MongoClient client;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_geo_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.auth.cms-token", () -> "cms-test-token");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> Base64.getEncoder().encodeToString(
                "geo-cursor-fixture-key-32bytes!!!".getBytes(StandardCharsets.UTF_8)));
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.tazzzo.com");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
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
        changes.recordBaseline(com.tazzzo.common.audit.TestActors.TEST, "R1");
        Clock clock = Clock.systemUTC();
        new ServiceabilityService(new Tx(client), db, new DomainAudit(db, clock), clock).upsertServiceArea(
                new UpsertServiceAreaCommand("560001", "SA-1", List.of(new ServiceabilityRoute("FL-1", 0, true)), "seed", null));
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        outage = false;
    }

    private JsonNode body(String q, int status) {
        ResponseEntity<JsonNode> res = get("/v1/serviceability?" + q, JsonNode.class);
        assertThat(res.getStatusCode().value()).as(q + " -> " + res.getBody()).isEqualTo(status);
        return res.getBody();
    }

    @Test
    void a_coordinate_resolves_through_the_provider_to_the_same_answer_as_its_pin() {
        JsonNode viaGeo = body("lat=12.9&lng=77.6", 200);
        JsonNode viaPin = body("pin=560001", 200);
        assertThat(viaGeo.get("serviceable").asBoolean()).isTrue();
        assertThat(viaGeo.get("serviceAreaId").asText()).isEqualTo("SA-1");
        assertThat(viaGeo.get("serviceAreaVersion")).isEqualTo(viaPin.get("serviceAreaVersion"));
        assertThat(viaGeo.toString().toLowerCase()).doesNotContain("fulfillment").doesNotContain("12.9").doesNotContain("77.6");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void a_point_with_no_pin_is_outside_coverage_not_an_error() {
        JsonNode b = body("lat=20.0&lng=70.0", 200);
        assertThat(b.get("serviceable").asBoolean()).isFalse();
        assertThat(b.has("serviceAreaId")).isFalse();
    }

    @Test
    void a_provider_answer_that_is_not_a_pin_is_never_trusted() {
        JsonNode b = body("lat=1.0&lng=1.0", 200);
        assertThat(b.get("serviceable").asBoolean()).isFalse();
    }

    @Test
    void malformed_or_ambiguous_coordinates_never_reach_the_provider() {
        for (String q : new String[]{"lat=12.9", "lng=77.6", "lat=abc&lng=1", "lat=NaN&lng=1", "lat=91&lng=1", "lat=1&lng=181",
                "lat=Infinity&lng=1", "pin=560001&lat=12.9&lng=77.6", "lat=%20&lng=1"}) {
            body(q, 400);
        }
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void a_provider_outage_is_503_never_a_guess_and_leaks_nothing() {
        outage = true;
        ResponseEntity<JsonNode> res = get("/v1/serviceability?lat=12.9&lng=77.6", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().toString()).doesNotContain("12.9").doesNotContain("GeoProvider");
    }

    @Test
    void list_and_pdp_stay_pin_only_even_with_a_provider() {
        ResponseEntity<JsonNode> res = get("/v1/categories/TZC-000001/products?lat=12.9&lng=77.6", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void the_pin_path_is_unchanged() {
        assertThat(body("pin=560001", 200).get("serviceable").asBoolean()).isTrue();
        body("", 400);
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void the_provider_is_never_called_for_a_request_the_limiter_refuses() {
        com.tazzzo.catalog.consumer.ConsumerAdmissionGate refusing = new com.tazzzo.catalog.consumer.ConsumerAdmissionGate(null, null) {
            @Override
            public void charge(com.tazzzo.catalog.consumer.ConsumerObservability.Route route,
                               com.tazzzo.catalog.consumer.ConsumerIdentity identity, long units) {
                throw new com.tazzzo.catalog.consumer.ConsumerFailures.RateLimited(java.time.Duration.ofSeconds(1));
            }
        };
        Clock clock = Clock.systemUTC();
        var service = new com.tazzzo.commerce.read.CommerceServiceabilityService(refusing,
                new ServiceabilityService(new Tx(client), db, new DomainAudit(db, clock), clock));
        int[] providerCalls = {0};
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolve(() -> {
            providerCalls[0]++;
            return null;
        }, new com.tazzzo.catalog.consumer.ConsumerIdentity("203.0.113.9", Optional.empty())))
                .isInstanceOf(com.tazzzo.catalog.consumer.ConsumerFailures.RateLimited.class);
        assertThat(providerCalls[0]).as("admission is charged BEFORE the geo lookup").isZero();
    }
}
