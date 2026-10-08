package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.common.audit.TestActors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trusted storefront caller over real HTTP with a real Redis: a request carrying a valid
 * {@code X-Tazzzo-Caller} / {@code X-Tazzzo-Caller-Secret} pair is charged to its OWN bucket, never the
 * (shared) client-IP bucket, and a bad credential is simply an ordinary client — never a 401.
 *
 * <p>Every request in this suite comes from 127.0.0.1, which is not a trusted proxy, so the IP bucket is the
 * same for every call; buckets are flushed before each test. {@code /v1/app-config} costs exactly 1 unit, so
 * the capacities below are request counts. Refill is ~one unit per 1000 s: nothing refills mid-test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class TrustedCallerRateLimitIT extends AbstractConsumerIT {

    static final String STOREFRONT_SECRET = "it-storefront-secret-7f3a9c2e5b1d4086a2c4";
    static final String PARTNER_SECRET = "it-partner-secret-0b8e6d4c2a1f3957e8d0b";
    static final String LOG_PROBE_SECRET = "it-log-probe-secret-5c1e9a7d3b2f4068c6e2";
    static final String WRONG_SECRET = "it-storefront-secret-7f3a9c2e5b1d4086a2cX";
    static final int IP_CAPACITY = 3;
    static final int CALLER_CAPACITY = 4;
    static final String APP_CONFIG = "/v1/app-config";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_trusted_caller_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> String.valueOf(IP_CAPACITY));
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "0.001");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "0.001");
        r.add("tazzzo.consumer-rate-limit.caller.capacity", () -> String.valueOf(CALLER_CAPACITY));
        r.add("tazzzo.consumer-rate-limit.caller.refill-per-second", () -> "0.001");
        r.add("tazzzo.consumer.trusted-callers[0].name", () -> "storefront");
        r.add("tazzzo.consumer.trusted-callers[0].secret", () -> STOREFRONT_SECRET);
        r.add("tazzzo.consumer.trusted-callers[1].name", () -> "partner");
        r.add("tazzzo.consumer.trusted-callers[1].secret", () -> PARTNER_SECRET);
        r.add("tazzzo.consumer.trusted-callers[2].name", () -> "log_probe");
        r.add("tazzzo.consumer.trusted-callers[2].secret", () -> LOG_PROBE_SECRET);
    }

    @Autowired MeterRegistry meters;

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
    }

    @BeforeEach
    void emptyBuckets() {
        flushRateLimitBuckets();
    }

    private static HttpHeaders caller(String name, String secret) {
        HttpHeaders h = new HttpHeaders();
        if (name != null) h.set("X-Tazzzo-Caller", name);
        if (secret != null) h.set("X-Tazzzo-Caller-Secret", secret);
        return h;
    }

    private int status(String path, HttpHeaders headers) {
        return get(path, headers, JsonNode.class).getStatusCode().value();
    }

    private int anonymous() {
        return status(APP_CONFIG, new HttpHeaders());
    }

    private int storefront() {
        return status(APP_CONFIG, caller("storefront", STOREFRONT_SECRET));
    }

    private double admissions(String route, String caller, String decision) {
        Counter c = meters.find(ConsumerObservability.TRUSTED_CALLER_ADMISSIONS)
                .tags("route", route, "caller", caller, "decision", decision).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void a_drained_ip_bucket_does_not_touch_the_trusted_caller_which_has_its_own_finite_bucket() {
        double allowedBefore = admissions("app_config", "storefront", "allowed");
        double limitedBefore = admissions("app_config", "storefront", "rate_limited");
        for (int i = 0; i < IP_CAPACITY; i++) assertThat(anonymous()).as("anonymous #" + i).isEqualTo(200);
        assertThat(anonymous()).as("the shared IP bucket is now empty").isEqualTo(429);

        for (int i = 0; i < CALLER_CAPACITY; i++) {
            assertThat(storefront()).as("trusted #" + i + " is charged to caller:storefront, not the IP").isEqualTo(200);
        }
        assertThat(storefront()).as("the caller bucket is finite: it limits, it is not a bypass").isEqualTo(429);
        assertThat(anonymous()).isEqualTo(429);

        assertThat(admissions("app_config", "storefront", "allowed") - allowedBefore).isEqualTo(CALLER_CAPACITY);
        assertThat(admissions("app_config", "storefront", "rate_limited") - limitedBefore).isEqualTo(1);
    }

    @Test
    void a_drained_caller_bucket_leaves_anonymous_clients_on_the_same_ip_untouched() {
        for (int i = 0; i < CALLER_CAPACITY; i++) assertThat(storefront()).isEqualTo(200);
        assertThat(storefront()).isEqualTo(429);
        for (int i = 0; i < IP_CAPACITY; i++) {
            assertThat(anonymous()).as("anonymous #" + i + ": the trusted calls spent nothing from the IP bucket").isEqualTo(200);
        }
        assertThat(anonymous()).isEqualTo(429);
    }

    @Test
    void each_trusted_caller_has_its_own_bucket() {
        for (int i = 0; i < CALLER_CAPACITY; i++) assertThat(storefront()).isEqualTo(200);
        assertThat(storefront()).isEqualTo(429);
        assertThat(status(APP_CONFIG, caller("partner", PARTNER_SECRET))).isEqualTo(200);
    }

    @Test
    void a_wrong_or_partial_credential_is_an_ordinary_client_charged_to_the_ip_bucket_and_never_refused() {
        assertThat(status(APP_CONFIG, caller("storefront", WRONG_SECRET))).as("wrong secret: not a 401").isEqualTo(200);
        assertThat(status(APP_CONFIG, caller("storefront", null))).as("missing secret").isEqualTo(200);
        assertThat(status(APP_CONFIG, caller("nobody", STOREFRONT_SECRET))).as("unknown name").isEqualTo(200);
        assertThat(anonymous()).as("all three were charged to the IP bucket, which is now empty").isEqualTo(429);
        assertThat(status(APP_CONFIG, caller("storefront", WRONG_SECRET))).as("and they are limited like anyone").isEqualTo(429);
        assertThat(status(APP_CONFIG, caller("partner", STOREFRONT_SECRET))).as("another caller's secret").isEqualTo(429);
        assertThat(storefront()).as("the real credential is still admitted on its own bucket").isEqualTo(200);
    }

    @Test
    void the_commerce_read_routes_honour_the_trusted_caller_too() {
        String pdp = "/v1/products/TZP-DOES-NOT-EXIST";
        for (int i = 0; i < IP_CAPACITY; i++) assertThat(anonymous()).isEqualTo(200);
        assertThat(status(pdp, new HttpHeaders())).as("anonymous PDP: IP bucket empty").isEqualTo(429);
        assertThat(status(pdp, caller("storefront", STOREFRONT_SECRET)))
                .as("trusted PDP is admitted (then answers the product's own 404)").isEqualTo(404);
    }

    /** {@code log_probe} is configured for this test alone, so its once-a-minute WARN window is unspent here. */
    @Test
    void nothing_logged_contains_a_secret_and_a_rejection_warns_once_with_the_name_only(CapturedOutput output) {
        assertThat(status(APP_CONFIG, caller("log_probe", WRONG_SECRET))).isEqualTo(200);
        assertThat(status(APP_CONFIG, caller("log_probe", STOREFRONT_SECRET))).isEqualTo(200);
        assertThat(status(APP_CONFIG, caller("log_probe", LOG_PROBE_SECRET))).isEqualTo(200);
        assertThat(storefront()).isEqualTo(200);

        String logged = output.getAll();
        assertThat(logged).doesNotContain(STOREFRONT_SECRET).doesNotContain(LOG_PROBE_SECRET).doesNotContain(WRONG_SECRET);
        assertThat(logged.lines().filter(l -> l.contains("trusted_caller_rejected caller=log_probe")).count())
                .as("two rejections within the minute: one WARN").isEqualTo(1);
        assertThat(logged).contains("trusted_caller_rejected caller=log_probe reason=wrong_secret");
    }
}
