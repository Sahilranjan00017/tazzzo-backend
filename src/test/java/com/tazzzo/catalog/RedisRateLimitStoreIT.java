package com.tazzzo.catalog;

import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.BucketDimension;
import com.tazzzo.catalog.ratelimit.BucketObservation;
import com.tazzzo.catalog.ratelimit.BucketSpec;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitProperties;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimiter;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import com.tazzzo.catalog.ratelimit.RedisRateLimitStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q5-MECH-1 evidence. The point of these tests is NOT that Redis gets called — it is that limiter
 * state is genuinely SHARED across independently constructed application instances. Two catalogue
 * tasks serve consumers concurrently, so a bucket that lived in one process's memory would silently
 * multiply the effective allowance by the replica count.
 *
 * <p>Every test therefore builds TWO complete, separate stacks — two connection factories, two
 * templates, two stores — pointed at one Redis. Replace either side with process-local state and
 * these tests fail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRateLimitStoreIT {

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    /** "Instance A" and "instance B" — two ECS tasks, as far as the store is concerned. */
    private RateLimitStore instanceA;
    private RateLimitStore instanceB;
    private StringRedisTemplate templateA;

    private static StringRedisTemplate templateFor(String host, int port) {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    @BeforeAll
    void twoIndependentStacks() {
        templateA = templateFor(REDIS.getHost(), REDIS.getMappedPort(6379));
        instanceA = new RedisRateLimitStore(templateA);
        instanceB = new RedisRateLimitStore(templateFor(REDIS.getHost(), REDIS.getMappedPort(6379)));
    }

    @BeforeEach
    void flush() {
        templateA.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private BucketSpec bucket(String key, long capacity, double refill) {
        BucketDimension dimension = key.startsWith("install") ? BucketDimension.INSTALLATION : BucketDimension.IP;
        return new BucketSpec(dimension, key + ":" + UUID.randomUUID(), capacity, refill);
    }

    // ---------- THE test: one bucket, two instances ----------

    @Test
    void a_limit_reached_through_instance_A_is_observed_by_instance_B() {
        BucketSpec shared = bucket("ip:203.0.113.1", 3, 0.01);   // slow refill: no accidental top-up

        assertThat(instanceA.tryConsume(List.of(shared), 1)).isInstanceOf(Admission.Allowed.class);
        assertThat(instanceA.tryConsume(List.of(shared), 1)).isInstanceOf(Admission.Allowed.class);
        assertThat(instanceB.tryConsume(List.of(shared), 1))
                .as("instance B must see the two tokens A already spent")
                .isInstanceOf(Admission.Allowed.class);

        Admission denied = instanceB.tryConsume(List.of(shared), 1);
        assertThat(denied)
                .as("the bucket is exhausted for the SERVICE, not for one process")
                .isInstanceOf(Admission.RateLimited.class);
        assertThat(instanceA.tryConsume(List.of(shared), 1))
                .as("and A agrees, because there is one bucket")
                .isInstanceOf(Admission.RateLimited.class);
    }

    @Test
    void cost_is_weighted_not_a_request_count() {
        BucketSpec shared = bucket("ip:weighted", 10, 0.01);

        assertThat(instanceA.tryConsume(List.of(shared), 7)).isInstanceOf(Admission.Allowed.class);
        assertThat(instanceB.tryConsume(List.of(shared), 7))
                .as("one expensive request can exhaust what many cheap ones would not")
                .isInstanceOf(Admission.RateLimited.class);
        assertThat(instanceB.tryConsume(List.of(shared), 3))
                .as("exactly the remainder still fits")
                .isInstanceOf(Admission.Allowed.class);
    }

    // ---------- Q5-ATOMIC-1 ----------

    @Test
    void when_one_bucket_cannot_pay_NEITHER_is_debited() {
        BucketSpec ip = bucket("ip:atomic", 100, 0.01);
        BucketSpec install = bucket("install:atomic", 2, 0.01);

        assertThat(instanceA.tryConsume(List.of(ip, install), 2)).isInstanceOf(Admission.Allowed.class);
        // install is now empty; ip still has 98.
        assertThat(instanceB.tryConsume(List.of(ip, install), 1))
                .isInstanceOf(Admission.RateLimited.class);

        // The IP bucket must be untouched by that refusal — otherwise a caller could drain the
        // shared IP bucket for free by presenting an exhausted installation id.
        assertThat(instanceA.tryConsume(List.of(ip), 98))
                .as("all 98 remaining IP tokens are still there")
                .isInstanceOf(Admission.Allowed.class);
        assertThat(instanceA.tryConsume(List.of(ip), 1)).isInstanceOf(Admission.RateLimited.class);
    }

    @Test
    void retry_after_is_the_longest_wait_among_the_deficient_buckets() {
        BucketSpec fast = bucket("ip:retry", 1, 10.0);      // needs 1 token -> ~100ms
        BucketSpec slow = bucket("install:retry", 1, 0.5);  // needs 1 token -> ~2s
        instanceA.tryConsume(List.of(fast, slow), 1);       // drain both

        Admission a = instanceB.tryConsume(List.of(fast, slow), 1);
        assertThat(a).isInstanceOf(Admission.RateLimited.class);
        assertThat(((Admission.RateLimited) a).retryAfter().toMillis())
                .as("the client must wait for EVERY bucket, so the slowest one decides")
                .isBetween(1500L, 2500L);
    }

    // ---------- refill uses REDIS server time, not a JVM clock ----------

    @Test
    void tokens_refill_over_time() throws Exception {
        BucketSpec b = bucket("ip:refill", 1, 20.0);        // 20/sec
        assertThat(instanceA.tryConsume(List.of(b), 1)).isInstanceOf(Admission.Allowed.class);
        assertThat(instanceA.tryConsume(List.of(b), 1)).isInstanceOf(Admission.RateLimited.class);

        Thread.sleep(250);
        assertThat(instanceB.tryConsume(List.of(b), 1))
                .as("refill is computed from Redis TIME, so both instances see the same clock")
                .isInstanceOf(Admission.Allowed.class);
    }

    // ---------- the limiter on top ----------

    @Test
    void the_limiter_always_applies_the_ip_bucket_and_only_then_the_installation_bucket() {
        ConsumerRateLimitProperties.Bucket ip = new ConsumerRateLimitProperties.Bucket();
        ip.setCapacity(2);
        ip.setRefillPerSecond(0.01);
        ConsumerRateLimitProperties.Bucket install = new ConsumerRateLimitProperties.Bucket();
        install.setCapacity(100);
        install.setRefillPerSecond(0.01);

        ConsumerRateLimiter a = new ConsumerRateLimiter(instanceA, ip, install);
        ConsumerRateLimiter b = new ConsumerRateLimiter(instanceB, ip, install);
        String clientIp = "203.0.113." + (int) (Math.random() * 200 + 1);

        assertThat(a.admit(clientIp, Optional.of("install-one"), 1)).isInstanceOf(Admission.Allowed.class);
        assertThat(b.admit(clientIp, Optional.of("install-two"), 1))
                .as("a different installation id does not escape the IP bucket")
                .isInstanceOf(Admission.Allowed.class);
        assertThat(b.admit(clientIp, Optional.of("install-three"), 1))
                .as("ROTATING THE INSTALLATION ID BUYS NOTHING — the IP bucket is the backstop")
                .isInstanceOf(Admission.RateLimited.class);
        assertThat(a.admit(clientIp, Optional.empty(), 1))
                .as("omitting it entirely buys nothing either")
                .isInstanceOf(Admission.RateLimited.class);
    }

    @Test
    void an_unresolved_client_ip_is_never_silently_allowed() {
        ConsumerRateLimitProperties.Bucket b = new ConsumerRateLimitProperties.Bucket();
        b.setCapacity(10);
        b.setRefillPerSecond(1);
        ConsumerRateLimiter limiter = new ConsumerRateLimiter(instanceA, b, b);

        assertThat(limiter.admit(null, Optional.empty(), 1)).isInstanceOf(Admission.Unavailable.class);
        assertThat(limiter.admit("  ", Optional.empty(), 1)).isInstanceOf(Admission.Unavailable.class);
    }

    // ---------- Q5-FAIL-1: a store outage is UNAVAILABLE, never allowed, never 429 ----------

    @Test
    void an_unreachable_store_is_UNAVAILABLE_and_never_falls_back_in_memory() {
        RateLimitStore broken = new RedisRateLimitStore(templateFor("127.0.0.1", 6));  // closed port
        Admission result = broken.tryConsume(List.of(bucket("ip:down", 10, 1)), 1);

        assertThat(result)
                .as("a store outage is not 'you exceeded your rate' and is not an allow")
                .isInstanceOf(Admission.Unavailable.class);
        assertThat(result).isNotInstanceOf(Admission.Allowed.class);
        assertThat(result).isNotInstanceOf(Admission.RateLimited.class);
    }

    // ---------- Q5-OBS-1: observations come from the SAME execution as the verdict ----------

    @Test
    void an_admitted_request_reports_post_debit_remaining_per_bucket() {
        BucketSpec ip = bucket("ip:obs", 10, 0.01);
        BucketSpec install = bucket("install:obs", 50, 0.01);

        Admission a = instanceA.tryConsume(List.of(ip, install), 4);
        assertThat(a).isInstanceOf(Admission.Allowed.class);
        List<BucketObservation> obs = ((Admission.Allowed) a).observations();
        assertThat(obs).hasSize(2);
        assertThat(obs.get(0).dimension()).isEqualTo(BucketDimension.IP);
        assertThat(obs.get(0).capacity()).isEqualTo(10);
        assertThat(obs.get(0).remaining()).as("10 - 4, post-debit").isEqualTo(6.0);
        assertThat(obs.get(1).dimension()).isEqualTo(BucketDimension.INSTALLATION);
        assertThat(obs.get(1).remaining()).isEqualTo(46.0);
        assertThat(obs.get(0).saturation()).isEqualTo(0.4);
    }

    @Test
    void a_refused_request_reports_the_undebited_available_tokens() {
        BucketSpec ip = bucket("ip:obs2", 5, 0.01);
        instanceA.tryConsume(List.of(ip), 3);                     // 2 left

        Admission denied = instanceB.tryConsume(List.of(ip), 3);   // needs 3, has 2
        assertThat(denied).isInstanceOf(Admission.RateLimited.class);
        List<BucketObservation> obs = ((Admission.RateLimited) denied).observations();
        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).remaining())
                .as("what was available when refused -- and NOT debited by the refusal")
                .isEqualTo(2.0);
        assertThat(instanceA.tryConsume(List.of(ip), 2))
                .as("the 2 tokens are provably still there")
                .isInstanceOf(Admission.Allowed.class);
    }

    /**
     * Exactly-full then exactly-spent: the observation must say 0, and it must come from the debit
     * that just happened, not from a read before it or a guess about capacity.
     */
    @Test
    void spending_the_whole_bucket_observes_zero_remaining_and_full_saturation() {
        BucketSpec ip = bucket("ip:obs3", 294, 0.0001);
        Admission a = instanceA.tryConsume(List.of(ip), 294);
        assertThat(a).isInstanceOf(Admission.Allowed.class);
        BucketObservation o = ((Admission.Allowed) a).observations().get(0);
        assertThat(o.remaining()).isEqualTo(0.0);
        assertThat(o.saturation()).isEqualTo(1.0);
    }

    @Test
    void an_observation_never_carries_the_redis_key() {
        BucketSpec ip = bucket("ip:secret-client-address", 10, 1);
        Admission a = instanceA.tryConsume(List.of(ip), 1);
        BucketObservation o = ((Admission.Allowed) a).observations().get(0);
        assertThat(o.toString())
                .as("only dimension and numbers cross the store boundary")
                .doesNotContain("secret-client-address").doesNotContain("rl:");
    }

    @Test
    void unavailable_carries_no_fabricated_observation() {
        RateLimitStore broken = new RedisRateLimitStore(templateFor("127.0.0.1", 6));
        Admission result = broken.tryConsume(List.of(bucket("ip:down2", 10, 1)), 1);
        assertThat(result).isInstanceOf(Admission.Unavailable.class);
        // Unavailable has no observations() at all -- the type makes fabrication unrepresentable.
    }

    // ---------- an IMPOSSIBLE cost is an invariant fault, not throttling ----------

    /**
     * A full bucket only ever reaches its capacity, so a cost above capacity can NEVER be admitted.
     * Returning RATE_LIMITED with a finite Retry-After would be a lie the client obeys forever.
     */
    @Test
    void a_cost_above_a_bucket_capacity_is_UNAVAILABLE_not_rate_limited() {
        BucketSpec small = bucket("ip:impossible", 10, 1.0);

        Admission verdict = instanceA.tryConsume(List.of(small), 11);
        assertThat(verdict).isInstanceOf(Admission.Unavailable.class);
        assertThat(verdict).isNotInstanceOf(Admission.RateLimited.class);
        assertThat(((Admission.Unavailable) verdict).reason()).contains("exceeds bucket capacity");

        assertThat(instanceB.tryConsume(List.of(small), 10))
                .as("and the bucket was NOT debited by the impossible request")
                .isInstanceOf(Admission.Allowed.class);
    }

    @Test
    void a_cost_above_the_INSTALLATION_capacity_leaves_the_IP_bucket_untouched_too() {
        BucketSpec ip = bucket("ip:impossible2", 100, 1.0);
        BucketSpec install = bucket("install:impossible2", 5, 1.0);

        assertThat(instanceA.tryConsume(List.of(ip, install), 6))
                .isInstanceOf(Admission.Unavailable.class);
        assertThat(instanceB.tryConsume(List.of(ip), 100))
                .as("all 100 IP tokens survive an impossible request")
                .isInstanceOf(Admission.Allowed.class);
    }

    @Test
    void a_cost_exactly_equal_to_capacity_is_still_admissible() {
        BucketSpec exact = bucket("ip:exact", 10, 0.01);
        assertThat(instanceA.tryConsume(List.of(exact), 10))
                .as("capacity is the burst allowance, and spending all of it is legitimate")
                .isInstanceOf(Admission.Allowed.class);
        assertThat(instanceB.tryConsume(List.of(exact), 1)).isInstanceOf(Admission.RateLimited.class);
    }

    @Test
    void no_buckets_is_UNAVAILABLE_rather_than_a_silent_allow() {
        assertThat(instanceA.tryConsume(List.of(), 1)).isInstanceOf(Admission.Unavailable.class);
    }
}
