package com.tazzzo.catalog;

import com.tazzzo.commerce.read.CommerceReadReadiness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10C §9 — the internal readiness seam for the public commerce read surface. NOT an HTTP
 * endpoint (this repository keeps {@code /actuator/**} off the wire); this proves the component
 * itself reports accurately, and never exposes anything beyond booleans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceReadReadinessIT extends AbstractConsumerIT {

    @Autowired CommerceReadReadiness readiness;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.data.mongodb.database", () -> "tazzzo_commerce_readiness_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer.cursor-hmac-key-b64", () -> CommerceApiIT.FIXTURE_KEY_B64);
        r.add("tazzzo.freshness.enabled", () -> "true");
        r.add("tazzzo.media.public-base-url", () -> "https://cdn.tazzzo.com");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "100000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "100000");
    }

    @Test void reports_base_and_list_ready_when_every_dependency_is_present() {
        CommerceReadReadiness.Report report = readiness.check();
        assertThat(report.mongoReachable()).isTrue();
        assertThat(report.cursorSigningConfigured()).isTrue();
        assertThat(report.listServingReady()).as("tazzzo.freshness.enabled=true").isTrue();
        assertThat(report.mediaConfigured()).isTrue();
        assertThat(report.rateLimiterConfigured()).as("REDIS mode, not DISABLED").isTrue();
        assertThat(report.baseReady())
                .as("categories/children/PDP/serviceability need no cursor or freshness").isTrue();
        assertThat(report.listReady()).as("category-products additionally needs cursor + freshness").isTrue();
    }

    @Test void base_ready_does_not_require_cursor_signing_or_freshness() {
        // PR-10C final review #5: categories/children/PDP/serviceability never touch the cursor
        // codec or freshness -- a pure Report construction proves the split without a real outage.
        CommerceReadReadiness.Report cursorAndFreshnessMissing =
                new CommerceReadReadiness.Report(true, false, false, true, true);
        assertThat(cursorAndFreshnessMissing.baseReady())
                .as("Mongo + rate limiter is all base routes need").isTrue();
        assertThat(cursorAndFreshnessMissing.listReady())
                .as("category-products additionally needs cursor signing + freshness").isFalse();
    }

    @Test void mongo_down_fails_both_base_and_list_readiness() {
        CommerceReadReadiness.Report mongoDown =
                new CommerceReadReadiness.Report(false, true, true, true, true);
        assertThat(mongoDown.baseReady()).isFalse();
        assertThat(mongoDown.listReady()).isFalse();
    }

    @Test void media_unconfigured_never_gates_either_readiness_level() {
        CommerceReadReadiness.Report mediaMissing =
                new CommerceReadReadiness.Report(true, true, true, false, true);
        assertThat(mediaMissing.baseReady()).isTrue();
        assertThat(mediaMissing.listReady()).as("imagery degrades safely, never gates readiness").isTrue();
    }

    @Test void the_report_never_carries_a_uri_url_or_key_shaped_value() {
        String s = readiness.check().toString();
        assertThat(s).doesNotContain("mongodb://").doesNotContain("redis://")
                .doesNotContain("mongodb+srv://").doesNotContainIgnoringCase("password");
    }
}
