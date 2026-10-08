package com.tazzzo.catalog;

import com.tazzzo.catalog.ratelimit.ConsumerRateLimitConfig;
import com.tazzzo.catalog.ratelimit.ConsumerRateLimiter;
import com.tazzzo.catalog.ratelimit.TrustedCallerResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tazzzo.consumer.trusted-callers} wiring: absent is "no trusted caller", invalid fails at startup without
 * echoing the secret, a configured caller requires the caller bucket, and the documented ENVIRONMENT variable
 * names really bind (measured through a real {@link SystemEnvironmentPropertySource}, not assumed).
 */
class TrustedCallerWiringTest {

    static final String SECRET = "wiring-fixture-secret-0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsumerRateLimitConfig.class);

    private static final String[] REDIS = {
            "tazzzo.consumer-rate-limit.mode=REDIS",
            "tazzzo.consumer-rate-limit.redis-url=redis://localhost:6379",
            "tazzzo.consumer-rate-limit.trusted-proxy-cidrs=10.0.0.0/8",
            "tazzzo.consumer-rate-limit.ip.capacity=60",
            "tazzzo.consumer-rate-limit.ip.refill-per-second=1",
            "tazzzo.consumer-rate-limit.installation.capacity=30",
            "tazzzo.consumer-rate-limit.installation.refill-per-second=0.5"};

    @Test
    void absent_configuration_means_no_trusted_caller_and_the_limiter_still_starts() {
        runner.withPropertyValues(REDIS).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConsumerRateLimiter.class);
            assertThat(context.getBean(TrustedCallerResolver.class).hasCallers()).isFalse();
        });
    }

    @Test
    void a_trusted_caller_without_a_caller_bucket_fails_to_start() {
        runner.withPropertyValues(REDIS)
                .withPropertyValues("tazzzo.consumer.trusted-callers[0].name=storefront",
                        "tazzzo.consumer.trusted-callers[0].secret=" + SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("caller.capacity");
                    assertThat(stackTrace(context.getStartupFailure())).doesNotContain(SECRET);
                });
    }

    @Test
    void an_invalid_secret_fails_to_start_without_revealing_it() {
        String weak = "too-short-secret";
        runner.withPropertyValues("tazzzo.consumer-rate-limit.mode=DISABLED",
                        "tazzzo.consumer.trusted-callers[0].name=storefront",
                        "tazzzo.consumer.trusted-callers[0].secret=" + weak)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasStackTraceContaining("trusted-callers[0].secret");
                    assertThat(stackTrace(context.getStartupFailure())).doesNotContain(weak);
                });
    }

    @Test
    void the_documented_environment_variables_bind_and_authenticate() {
        runner.withPropertyValues(REDIS)
                .withPropertyValues("tazzzo.consumer-rate-limit.caller.capacity=100",
                        "tazzzo.consumer-rate-limit.caller.refill-per-second=10")
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("TAZZZO_CONSUMER_TRUSTEDCALLERS_0_NAME", "storefront",
                                        "TAZZZO_CONSUMER_TRUSTEDCALLERS_0_SECRET", SECRET))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TrustedCallerResolver resolver = context.getBean(TrustedCallerResolver.class);
                    assertThat(resolver.hasCallers()).isTrue();
                    assertThat(resolver.resolve("storefront", SECRET)).contains("storefront");
                    assertThat(resolver.resolve("storefront", SECRET + "x")).isEmpty();
                });
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
