package com.tazzzo.catalog;

import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitConfig;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitProperties;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimiter;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q5-c wiring. The limiter OWNS its Redis endpoint: production admission control must not depend
 * on whatever connection defaults an autoconfiguration happens to supply, and must not be silently
 * repointed by an unrelated future Redis feature. {@code RedisAutoConfiguration} is excluded from
 * the application for the same reason, so there is no localhost default to fall back onto.
 */
class ConsumerRateLimitWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsumerRateLimitConfig.class);

    private static String[] completeExcept(String... overrides) {
        String[] base = {
                "tazzzo.consumer-rate-limit.mode=REDIS",
                "tazzzo.consumer-rate-limit.redis-url=redis://localhost:6379",
                "tazzzo.consumer-rate-limit.trusted-proxy-cidrs=10.0.0.0/8",
                "tazzzo.consumer-rate-limit.ip.capacity=60",
                "tazzzo.consumer-rate-limit.ip.refill-per-second=1",
                "tazzzo.consumer-rate-limit.installation.capacity=30",
                "tazzzo.consumer-rate-limit.installation.refill-per-second=0.5"};
        String[] out = new String[base.length + overrides.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(overrides, 0, out, base.length, overrides.length);
        return out;
    }

    // ---------- the gap this commit closes ----------

    @Test
    void REDIS_without_an_explicit_redis_url_fails_to_start() {
        runner.withPropertyValues(completeExcept("tazzzo.consumer-rate-limit.redis-url="))
                .run(context -> assertThat(context)
                        .as("the limiter never inherits an endpoint default")
                        .hasFailed()
                        .getFailure().hasMessageContaining("redis-url"));
    }

    @Test
    void REDIS_with_a_malformed_redis_url_fails_to_start() {
        runner.withPropertyValues(completeExcept("tazzzo.consumer-rate-limit.redis-url=not a url"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void REDIS_with_an_explicit_redis_url_constructs_the_store_and_the_limiter() {
        // No Redis needs to be reachable: Lettuce connects lazily, so this proves WIRING.
        runner.withPropertyValues(completeExcept()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimitStore.class);
            assertThat(context).hasSingleBean(ConsumerRateLimiter.class);
            assertThat(context).hasSingleBean(ClientIpResolver.class);
        });
    }

    // ---------- DISABLED remains an intentional state ----------

    @Test
    void DISABLED_needs_no_redis_url_and_builds_no_limiter() {
        runner.withPropertyValues("tazzzo.consumer-rate-limit.mode=DISABLED").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RateLimitStore.class);
            assertThat(context).doesNotHaveBean(ConsumerRateLimiter.class);
            assertThat(context)
                    .as("fail-closed: no limiter exists to call, rather than a permissive one")
                    .hasSingleBean(ClientIpResolver.class);
            assertThat(context.getBean(ConsumerRateLimitProperties.Mode.class))
                    .isEqualTo(ConsumerRateLimitProperties.Mode.DISABLED);
        });
    }

    // ---------- the other REDIS prerequisites still hold ----------

    @Test
    void REDIS_still_refuses_incomplete_buckets_or_an_undecided_proxy_chain() {
        runner.withPropertyValues(completeExcept("tazzzo.consumer-rate-limit.ip.capacity=0"))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("ip.capacity"));
        runner.withPropertyValues(completeExcept("tazzzo.consumer-rate-limit.trusted-proxy-cidrs="))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("trusted-proxy-cidrs"));
    }

    @Test
    void a_missing_or_unrecognised_mode_fails_to_start() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().hasMessageContaining("NO default"));
        runner.withPropertyValues("tazzzo.consumer-rate-limit.mode=IN_MEMORY")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("DISABLED or REDIS"));
    }
}
