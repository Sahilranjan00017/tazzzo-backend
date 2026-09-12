package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q5-OBS-1 over real HTTP. The IP bucket capacity is EXACTLY the seeded root cost (294), so the
 * first request spends it to zero and the second is refused — which is what lets remaining and
 * saturation be asserted precisely, from the same Lua execution that decided.
 *
 * <p>Ordered: the bucket state is the fixture.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsumerObservabilityIT extends AbstractConsumerIT {

    private static final String INSTALL_ID = "install-abc-123-do-not-tag";

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_observability_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        // FIXTURE values (Q5-c): capacity == the seeded root cost, so it drains exactly to zero.
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "294");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "0.0001");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "0.0001");
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline("R1");
        eligibleProduct("TZP-1", "TZV-000001");
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private long timerCount(String name, String... tags) {
        var t = registry.find(name).tags(tags).timer();
        return t == null ? 0 : t.count();
    }

    private double summaryMax(String name, String... tags) {
        var s = registry.find(name).tags(tags).summary();
        return s == null ? Double.NaN : s.max();
    }

    private double summaryMin(String name, String... tags) {
        var s = registry.find(name).tags(tags).summary();
        if (s == null) return Double.NaN;
        // DistributionSummary has no min(); with a single recording, total == that value.
        return s.count() == 1 ? s.totalAmount() : Double.NaN;
    }

    // ---------- 1. an admitted root ----------

    @Test @Order(1)
    void an_admitted_root_records_success_cost_zero_remaining_full_saturation_and_seven_probes() {
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(200);

        assertThat(counter(ConsumerObservability.REQUESTS, "route", "root", "outcome", "success"))
                .isEqualTo(1);
        assertThat(timerCount(ConsumerObservability.REQUEST_DURATION, "route", "root", "outcome", "success"))
                .isEqualTo(1);

        assertThat(summaryMax(ConsumerObservability.RATE_LIMIT_COST, "route", "root"))
                .as("the COMPUTED cost on the seeded tree -- test evidence, never a production constant")
                .isEqualTo(294);

        assertThat(summaryMin(ConsumerObservability.RATE_LIMIT_REMAINING,
                "route", "root", "dimension", "ip", "decision", "allowed"))
                .as("capacity 294, cost 294: post-debit remaining is 0 -- from the SAME Lua execution")
                .isEqualTo(0.0);
        assertThat(summaryMin(ConsumerObservability.RATE_LIMIT_SATURATION,
                "route", "root", "dimension", "ip", "decision", "allowed"))
                .isEqualTo(1.0);

        assertThat(timerCount(ConsumerObservability.PROBE_DURATION, "route", "root", "result", "hit")
                + timerCount(ConsumerObservability.PROBE_DURATION, "route", "root", "result", "miss"))
                .as("one probe per candidate: seven super-categories")
                .isEqualTo(7);
        assertThat(counter(ConsumerObservability.PROBE_FAILURES, "route", "root")).isZero();
    }

    // ---------- 2. a refused root ----------

    @Test @Order(2)
    void a_refused_root_records_rate_limited_and_observes_the_undebited_state() {
        ResponseEntity<JsonNode> res = get("/catalog/v1/categories", JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(429);

        assertThat(counter(ConsumerObservability.REQUESTS, "route", "root", "outcome", "rate_limited"))
                .isEqualTo(1);
        assertThat(counter(ConsumerObservability.REQUESTS, "route", "root", "outcome", "success"))
                .as("the success count did not move").isEqualTo(1);
        assertThat(registry.find(ConsumerObservability.RATE_LIMIT_REMAINING)
                .tags("route", "root", "dimension", "ip", "decision", "rate_limited").summary())
                .as("a refusal still observes the bucket it refused on").isNotNull();
        assertThat(summaryMax(ConsumerObservability.RATE_LIMIT_COST, "route", "root"))
                .as("the cost is recorded whatever the verdict").isEqualTo(294);
        assertThat(timerCount(ConsumerObservability.PROBE_DURATION, "route", "root", "result", "hit")
                + timerCount(ConsumerObservability.PROBE_DURATION, "route", "root", "result", "miss"))
                .as("a refused request ran ZERO probes, so the timer did not move").isEqualTo(7);
    }

    // ---------- 3. the installation dimension ----------

    @Test @Order(3)
    void an_installation_header_yields_both_dimensions_and_no_raw_identifier_anywhere() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Tazzzo-Installation-Id", INSTALL_ID);
        get("/catalog/v1/categories", h, JsonNode.class);   // 429 again -- both buckets observed

        assertThat(registry.find(ConsumerObservability.RATE_LIMIT_REMAINING)
                .tags("dimension", "installation").summary())
                .as("the installation bucket was observed").isNotNull();
        assertThat(registry.find(ConsumerObservability.RATE_LIMIT_REMAINING)
                .tags("dimension", "ip").summary()).isNotNull();

        for (Meter meter : registry.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getValue())
                        .as("meter %s tag %s must never carry the installation id", meter.getId().getName(), tag.getKey())
                        .doesNotContain(INSTALL_ID).doesNotContain("install-abc");
            }
        }
    }

    // ---------- 4. no actuator surface ----------

    @Test @Order(4)
    void actuator_is_on_the_classpath_and_still_not_a_surface() {
        ResponseEntity<JsonNode> health = get("/actuator/health", JsonNode.class);
        assertThat(health.getStatusCode().value()).isEqualTo(404);
        assertThat(health.getBody().at("/error/code").asText()).isEqualTo("NO_SUCH_ENDPOINT");
        assertThat(get("/actuator/prometheus", JsonNode.class).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/actuator/metrics", JsonNode.class).getStatusCode().value()).isEqualTo(404);
    }

    // ---------- 5. the cardinality / privacy guard over the WHOLE registry ----------

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern TAXONOMY_ID = Pattern.compile("\\bTZ[SCGV]-");
    private static final Pattern PRODUCT_ID = Pattern.compile("\\bTZP-");
    private static final Pattern REQUEST_ID = Pattern.compile("\\breq_[0-9a-f]{8,}");

    @Test @Order(5)
    void every_consumer_meter_uses_only_the_bounded_tag_vocabulary() {
        List<String> violations = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            if (!meter.getId().getName().startsWith("tazzzo.catalog.consumer.")) continue;
            for (Tag tag : meter.getId().getTags()) {
                String k = tag.getKey();
                String v = tag.getValue();
                if (!ConsumerObservability.ALLOWED_TAG_KEYS.contains(k)) {
                    violations.add(meter.getId().getName() + " has unexpected tag key " + k);
                }
                if (IPV4.matcher(v).find() || v.contains(":") && v.contains("::")) {
                    violations.add(meter.getId().getName() + " tag " + k + " looks like an IP: " + v);
                }
                if (TAXONOMY_ID.matcher(v).find() || PRODUCT_ID.matcher(v).find()) {
                    violations.add(meter.getId().getName() + " tag " + k + " carries an id: " + v);
                }
                if (REQUEST_ID.matcher(v).find() || v.startsWith("rl:") || v.equals("R1")
                        || v.startsWith("/")) {
                    violations.add(meter.getId().getName() + " tag " + k + " carries request/key/release/path: " + v);
                }
            }
        }
        assertThat(violations).as("cardinality is a contract, not a convention").isEmpty();
    }
}
