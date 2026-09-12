package com.tazzzo.catalog;

import com.mongodb.MongoException;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.consumer.ConsumerReleaseResolver;
import com.tazzzo.catalog.consumer.ConsumerTaxonomyService;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitProperties;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimiter;
import com.tazzzo.catalog.schema.SnapshotTaxonomyReader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TR-4B failure semantics under observation: a probe that ERRORS fails the request closed (503)
 * and is counted as a failure — never shown, never hidden, never silently skipped.
 *
 * <p>Collaborators are hand-written stubs (Mockito cannot instrument classes on this JVM): a real
 * Mongo find cannot be made to throw on demand, and the property under test is the service's
 * reaction, not Mongo's behaviour.
 */
class ConsumerProbeFailureTest {

    /** A MongoDatabase whose products collection cannot be reached. */
    private static MongoDatabase failingDatabase() {
        return (MongoDatabase) Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(),
                new Class<?>[]{MongoDatabase.class}, (proxy, method, args) -> {
                    if ("getCollection".equals(method.getName())) {
                        throw new MongoException("simulated probe failure");
                    }
                    if ("toString".equals(method.getName())) {
                        return "failing MongoDatabase";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SnapshotTaxonomyReader oneSuperCategory() {
        return new SnapshotTaxonomyReader(null) {
            @Override
            public List<Document> nodesOfType(String releaseId, String nodeType) {
                return List.of(new Document("node_id", "TZS-000001").append("name", "Staples")
                        .append("status", "active"));
            }

            @Override
            public List<String> consumerVerticalIdsInSubtree(String releaseId, String nodeId) {
                return List.of("TZV-000001");
            }
        };
    }

    private static ConsumerReleaseResolver alwaysR1() {
        return new ConsumerReleaseResolver(null) {
            @Override
            public String resolve(String explicitRelease) {
                return "R1";
            }
        };
    }

    private static ObjectProvider<ConsumerRateLimiter> alwaysAllowing() {
        ConsumerRateLimitProperties.Bucket b = new ConsumerRateLimitProperties.Bucket();
        ConsumerRateLimiter limiter = new ConsumerRateLimiter(null, b, b) {
            @Override
            public Admission admit(String clientIp, Optional<String> installationId, int cost) {
                return new Admission.Allowed(List.of());
            }
        };
        return new ObjectProvider<>() {
            @Override
            public ConsumerRateLimiter getObject(Object... args) {
                return limiter;
            }

            @Override
            public ConsumerRateLimiter getObject() {
                return limiter;
            }

            @Override
            public ConsumerRateLimiter getIfAvailable() {
                return limiter;
            }

            @Override
            public ConsumerRateLimiter getIfUnique() {
                return limiter;
            }
        };
    }

    @Test
    void a_probe_error_fails_closed_and_increments_the_failure_counter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerObservability observe = new ConsumerObservability(registry);
        ConsumerTaxonomyService service = new ConsumerTaxonomyService(
                oneSuperCategory(), alwaysR1(), failingDatabase(), alwaysAllowing(), observe);

        assertThatThrownBy(() -> service.root(null, "203.0.113.9", Optional.empty()))
                .as("unknown is neither shown nor hidden -- the request fails")
                .isInstanceOf(ConsumerFailures.Unavailable.class);

        assertThat(registry.find(ConsumerObservability.PROBE_FAILURES).tags("route", "root").counter().count())
                .isEqualTo(1);
        assertThat(registry.find(ConsumerObservability.PROBE_DURATION)
                .tags("route", "root", "result", "error").timer().count()).isEqualTo(1);
        // The request OUTCOME is not asserted here: it is recorded at the controller's clock
        // boundary, which this service-level test deliberately does not include. The HTTP suites
        // cover it (ConsumerRootStoreDownIT, ConsumerRootIpUnresolvableIT).
        assertThat(registry.find(ConsumerObservability.REQUESTS).counter())
                .as("the service records no request row of its own -- no double counting")
                .isNull();
    }

    /** A registry that refuses to create any meter. */
    private static SimpleMeterRegistry brokenRegistry() {
        return new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("registry down");
            }

            @Override
            protected Timer newTimer(Meter.Id id, DistributionStatisticConfig cfg, PauseDetector pd) {
                throw new IllegalStateException("registry down");
            }

            @Override
            protected DistributionSummary newDistributionSummary(Meter.Id id, DistributionStatisticConfig cfg, double scale) {
                throw new IllegalStateException("registry down");
            }
        };
    }

    /** Q5-OBS-1's last rule: a broken registry must not break a valid request. */
    @Test
    void a_throwing_registry_does_not_change_the_business_result() {
        ConsumerObservability observe = new ConsumerObservability(brokenRegistry());

        assertThatCode(() -> {
            observe.request(ConsumerObservability.Route.ROOT, ConsumerObservability.Outcome.SUCCESS, Duration.ZERO);
            observe.cost(ConsumerObservability.Route.ROOT, 1);
            observe.admission(ConsumerObservability.Route.ROOT, new Admission.Allowed(List.of()));
            observe.probe(ConsumerObservability.Route.ROOT, ConsumerObservability.ProbeResult.HIT, Duration.ZERO);
        }).as("instrumentation is subordinate to the business result").doesNotThrowAnyException();

        // And the full request path with a broken registry still returns the business answer.
        ConsumerTaxonomyService service = new ConsumerTaxonomyService(
                oneSuperCategory(), alwaysR1(), failingDatabase(), alwaysAllowing(), observe);
        assertThatThrownBy(() -> service.root(null, "203.0.113.9", Optional.empty()))
                .as("the BUSINESS failure (probe error) is what surfaces, not the registry fault")
                .isInstanceOf(ConsumerFailures.Unavailable.class)
                .hasMessageContaining("probe");
    }
}
