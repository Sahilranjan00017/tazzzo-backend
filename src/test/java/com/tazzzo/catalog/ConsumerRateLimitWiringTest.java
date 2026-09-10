package com.tazzzo.catalog;

import com.tazzzo.catalog.ratelimit.ClientIpResolver;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitConfig;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimitProperties;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimiter;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                        .getFailure().hasStackTraceContaining("redis-url"));
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
                        .getFailure().hasStackTraceContaining("ip.capacity"));
        runner.withPropertyValues(completeExcept("tazzzo.consumer-rate-limit.trusted-proxy-cidrs="))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("trusted-proxy-cidrs"));
    }

    @Test
    void a_missing_or_unrecognised_mode_fails_to_start() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().hasStackTraceContaining("NO default"));
        runner.withPropertyValues("tazzzo.consumer-rate-limit.mode=IN_MEMORY")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("exactly DISABLED or REDIS"));
    }

    /**
     * ONE parser decides whether the limiter exists. {@code RedisModeCondition} calls
     * {@code resolvedMode()} — the same method that validates the property — instead of
     * {@code @ConditionalOnProperty}, which compares case-INSENSITIVELY and would therefore have
     * registered the Redis beans for {@code mode=redis} while the validator rejected that spelling.
     * Two parsers deciding whether a security control exists IS the split.
     */
    @ParameterizedTest
    @ValueSource(strings = {"redis", "Redis", "REDis", "disabled", "Disabled", "Disable",
            "REDIS_MODE", "IN_MEMORY", "true"})
    void a_non_canonical_mode_spelling_fails_to_start(String mode) {
        runner.withPropertyValues("tazzzo.consumer-rate-limit.mode=" + mode)
                .run(context -> assertThat(context)
                        .as("'" + mode + "' must not be normalised into a mode")
                        .hasFailed());
    }

    /**
     * MEASURED, not assumed: Spring Boot's relaxed binder TRIMS String property values, so
     * surrounding whitespace never reaches application code — {@code " DISABLED "} arrives as
     * {@code "DISABLED"}. Whitespace therefore cannot be made a startup failure at this layer, and
     * does not need to be: with a single parser, both the validator and the bean condition observe
     * the same trimmed value, so there is no disagreement left to exploit. Case is different — the
     * binder preserves it, which is why the test above is the one that matters.
     */
    @ParameterizedTest
    @ValueSource(strings = {" DISABLED ", "DISABLED ", " DISABLED", "\tDISABLED"})
    void surrounding_whitespace_is_normalised_by_the_framework_before_any_code_sees_it(String mode) {
        runner.withPropertyValues("tazzzo.consumer-rate-limit.mode=" + mode).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ConsumerRateLimitProperties.Mode.class))
                    .isEqualTo(ConsumerRateLimitProperties.Mode.DISABLED);
            assertThat(context).doesNotHaveBean(RateLimitStore.class);
        });
    }

    // ---------- a credential-bearing endpoint must never reach the logs ----------

    /**
     * {@code redis-url} explicitly supports {@code username:password}, so a typo in a
     * credential-bearing endpoint must not put that credential into startup output. Neither the
     * message nor any nested cause may reproduce it.
     */
    @Test
    void a_malformed_secret_bearing_redis_url_fails_without_leaking_the_secret() {
        String secret = "SUPER_SECRET_PASSWORD";
        runner.withPropertyValues(completeExcept(
                        "tazzzo.consumer-rate-limit.redis-url=rediss://default:" + secret + "@bad host:6379"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    String rendered = renderFully(context.getStartupFailure());
                    assertThat(rendered)
                            .as("the whole causal chain and every stack trace, not just the message")
                            .doesNotContain(secret)
                            .doesNotContain("bad host")
                            .contains("redis-url");
                });
    }

    /** Message + stack trace of the failure AND of every cause, as a single string. */
    private static String renderFully(Throwable failure) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            out.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
            java.io.StringWriter writer = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(writer));
            out.append(writer);
            if (t.getCause() == t) {
                break;
            }
        }
        return out.toString();
    }
}
