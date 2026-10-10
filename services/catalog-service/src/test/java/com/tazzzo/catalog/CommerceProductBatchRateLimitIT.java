package com.tazzzo.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.common.audit.TestActors;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admission for {@code GET /v1/products:batch}: charged {@code 1 + distinct ids} BEFORE the first read, to the client-IP
 * bucket, or to the trusted caller's own bucket when a valid credential pair is sent. Buckets hold 60 units and refill
 * ~1 unit per 1000 s, so the arithmetic below is exact: nothing refills mid-test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {CatalogApplication.class, AbstractConsumerIT.ProbeCounting.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceProductBatchRateLimitIT extends AbstractConsumerIT {

    static final int CAPACITY = 60;
    static final String SECRET = "it-batch-storefront-secret-7f3a9c2e5b1d4086";
    static final String ROUTE = "commerce_products_batch";

    @Autowired MeterRegistry registry;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.mongodb.database", () -> "tazzzo_product_batch_ratelimit_it");
        r.add("tazzzo.schema.bootstrap-on-startup", () -> "false");
        r.add("tazzzo.scheduler.enabled", () -> "false");
        r.add("tazzzo.consumer-rate-limit.mode", () -> "REDIS");
        r.add("tazzzo.consumer-rate-limit.redis-url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("tazzzo.consumer-rate-limit.trusted-proxy-cidrs", () -> "10.99.99.0/24");
        r.add("tazzzo.consumer-rate-limit.ip.capacity", () -> String.valueOf(CAPACITY));
        r.add("tazzzo.consumer-rate-limit.ip.refill-per-second", () -> "0.001");
        r.add("tazzzo.consumer-rate-limit.installation.capacity", () -> "1000000");
        r.add("tazzzo.consumer-rate-limit.installation.refill-per-second", () -> "0.001");
        r.add("tazzzo.consumer-rate-limit.caller.capacity", () -> String.valueOf(CAPACITY));
        r.add("tazzzo.consumer-rate-limit.caller.refill-per-second", () -> "0.001");
        r.add("tazzzo.consumer.trusted-callers[0].name", () -> "storefront");
        r.add("tazzzo.consumer.trusted-callers[0].secret", () -> SECRET);
    }

    @BeforeAll
    void seed() {
        db.drop();
        schemaBootstrap.bootstrap(db);
        loader.load(db);
        changes.recordBaseline(TestActors.TEST, "R1");
        eligibleProduct("TZP-1", "TZV-000001");
    }

    @BeforeEach
    void emptyBuckets() {
        flushRateLimitBuckets();
        resetCounters();
    }

    /** {@code n} distinct, well-formed ids. */
    private static String ids(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "TZP-" + i).collect(Collectors.joining(","));
    }

    private ResponseEntity<JsonNode> batch(int n, HttpHeaders headers) {
        return get("/v1/products:batch?ids=" + ids(n), headers, JsonNode.class);
    }

    private ResponseEntity<JsonNode> batch(int n) {
        return batch(n, new HttpHeaders());
    }

    private static HttpHeaders storefront(String secret) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Tazzzo-Caller", "storefront");
        h.set("X-Tazzzo-Caller-Secret", secret);
        return h;
    }

    private double charged() {
        var s = registry.find(ConsumerObservability.RATE_LIMIT_COST).tags("route", ROUTE).summary();
        return s == null ? 0 : s.totalAmount();
    }

    private double callerAdmissions(String decision) {
        var c = registry.find(ConsumerObservability.TRUSTED_CALLER_ADMISSIONS)
                .tags("route", ROUTE, "caller", "storefront", "decision", decision).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void the_charge_is_one_plus_the_number_of_ids_so_a_big_batch_is_not_cheaper_than_the_gets_it_replaces() {
        double before = charged();
        assertThat(batch(50).getStatusCode().value()).isEqualTo(200);
        assertThat(charged() - before).isEqualTo(51);

        // 9 units are left: a 10-id batch needs 11 and is refused, a 1-id batch needs 2 and is served
        resetCounters();
        ResponseEntity<JsonNode> denied = batch(10);
        assertThat(denied.getStatusCode().value()).isEqualTo(429);
        assertThat(denied.getBody().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(denied.getBody().get("retryable").asBoolean()).isTrue();
        assertThat(Long.parseLong(denied.getHeaders().getFirst("Retry-After"))).isPositive();
        assertThat(denied.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(PRODUCT_FINDS.get()).as("refused BEFORE the first product read").isZero();
        assertThat(finds("taxonomy_snapshot_nodes")).isZero();
        assertThat(batch(1).getStatusCode().value()).isEqualTo(200);
        assertThat(batch(7).getStatusCode().value()).as("8 more: 9 - 2 = 7 left, 8 needed").isEqualTo(429);
        assertThat(batch(6).getStatusCode().value()).as("exactly the 7 left").isEqualTo(200);
    }

    @Test
    void hammering_with_large_batches_is_bounded_by_the_bucket_and_only_admitted_requests_touch_the_database() {
        int served = 0;
        int limited = 0;
        for (int i = 0; i < 100; i++) {
            int status = batch(20).getStatusCode().value();   // 21 units each; 60 / 21 -> exactly 2 fit
            if (status == 200) served++;
            else if (status == 429) limited++;
        }
        assertThat(served).isEqualTo(2);
        assertThat(limited).isEqualTo(98);
        assertThat(PRODUCT_FINDS.get()).as("one products find per ADMITTED request, none for the refused").isEqualTo(2);
    }

    @Test
    void duplicates_cannot_be_used_to_dodge_the_cap_or_to_pay_more_than_the_work() {
        String dup = java.util.Collections.nCopies(50, "TZP-1").stream().collect(Collectors.joining(","));
        double before = charged();
        assertThat(get("/v1/products:batch?ids=" + dup, JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(charged() - before).as("one distinct id: 1 + 1").isEqualTo(2);
    }

    @Test
    void malformed_requests_neither_drain_the_bucket_nor_reach_the_database() {
        for (int i = 0; i < 200; i++) {
            assertThat(get("/v1/products:batch?ids=tzp-" + i, JsonNode.class).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(PRODUCT_FINDS.get()).isZero();
        assertThat(charged()).isZero();
        assertThat(batch(50).getStatusCode().value()).as("the bucket is still full").isEqualTo(200);
    }

    @Test
    void a_trusted_caller_is_charged_to_its_own_bucket_and_the_client_ip_bucket_is_untouched() {
        double allowed = callerAdmissions("allowed");
        double limited = callerAdmissions("rate_limited");
        assertThat(batch(50, storefront(SECRET)).getStatusCode().value()).isEqualTo(200);
        assertThat(callerAdmissions("allowed") - allowed).isEqualTo(1);
        assertThat(batch(50, storefront(SECRET)).getStatusCode().value()).as("caller bucket: 9 left").isEqualTo(429);
        assertThat(callerAdmissions("rate_limited") - limited).isEqualTo(1);
        assertThat(batch(50).getStatusCode().value()).as("the ordinary IP bucket was never charged").isEqualTo(200);
    }

    @Test
    void a_wrong_secret_is_an_ordinary_client_charged_to_the_ip_bucket() {
        double allowed = callerAdmissions("allowed");
        assertThat(batch(50, storefront("x".repeat(40))).getStatusCode().value()).isEqualTo(200);
        assertThat(batch(50, storefront("x".repeat(40))).getStatusCode().value()).isEqualTo(429);
        assertThat(callerAdmissions("allowed") - allowed).as("never counted as the trusted caller").isZero();
        assertThat(batch(50, storefront(SECRET)).getStatusCode().value()).as("the real caller still has its own").isEqualTo(200);
    }
}
