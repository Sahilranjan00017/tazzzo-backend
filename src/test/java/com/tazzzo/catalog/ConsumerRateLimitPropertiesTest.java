package com.tazzzo.catalog;

import com.tazzzo.catalog.ratelimit.ConsumerRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Q5-c: the limiter has NO permissive production default, and a half-configured REDIS mode cannot
 * start. A permissive default is worse than none — it looks like a decision.
 */
class ConsumerRateLimitPropertiesTest {

    private ConsumerRateLimitProperties complete() {
        ConsumerRateLimitProperties p = new ConsumerRateLimitProperties();
        p.setMode("REDIS");
        p.setRedisUrl("redis://limiter.internal:6379");
        p.setTrustedProxyCidrs(List.of("10.0.0.0/8"));
        p.getIp().setCapacity(60);
        p.getIp().setRefillPerSecond(1.0);
        p.getInstallation().setCapacity(30);
        p.getInstallation().setRefillPerSecond(0.5);
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void a_blank_mode_is_a_startup_failure(String mode) {
        ConsumerRateLimitProperties p = new ConsumerRateLimitProperties();
        p.setMode(mode);
        assertThatThrownBy(p::resolvedMode)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO default");
    }

    @Test
    void an_unset_mode_is_a_startup_failure() {
        assertThatThrownBy(new ConsumerRateLimitProperties()::resolvedMode)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void an_unrecognised_mode_is_a_startup_failure_not_a_guess() {
        ConsumerRateLimitProperties p = new ConsumerRateLimitProperties();
        p.setMode("IN_MEMORY");
        assertThatThrownBy(p::resolvedMode)
                .as("there is deliberately no in-memory mode to fall into")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly DISABLED or REDIS");
    }

    @Test
    void disabled_is_an_intentional_state_and_needs_nothing_else() {
        ConsumerRateLimitProperties p = new ConsumerRateLimitProperties();
        p.setMode("DISABLED");
        assertThat(p.resolvedMode()).isEqualTo(ConsumerRateLimitProperties.Mode.DISABLED);
    }

    /**
     * Exact spellings only, so the ONE parser that decides whether the limiter exists cannot be
     * fed a value it would read differently from the validator. Whitespace is absent from this
     * list on purpose: Spring's binder trims String values before they reach here, which
     * ConsumerRateLimitWiringTest measures directly.
     */
    @ParameterizedTest
    @ValueSource(strings = {"redis", "Redis", "REDis", "disabled", "Disabled", "REDIS_MODE"})
    void a_non_canonical_spelling_is_never_normalised_into_a_mode(String mode) {
        ConsumerRateLimitProperties p = new ConsumerRateLimitProperties();
        p.setMode(mode);
        assertThatThrownBy(p::resolvedMode)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly DISABLED or REDIS");
    }

    @Test
    void redis_mode_refuses_to_start_without_bucket_numbers() {
        ConsumerRateLimitProperties p = complete();
        p.getIp().setCapacity(0);
        assertThatThrownBy(p::requireCompleteForRedis)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ip.capacity");

        ConsumerRateLimitProperties q = complete();
        q.getInstallation().setRefillPerSecond(0);
        assertThatThrownBy(q::requireCompleteForRedis)
                .hasMessageContaining("installation.capacity");
    }

    /**
     * The limiter owns its endpoint. Without this check a deployment that never configured one
     * would inherit whatever an autoconfiguration supplies — a localhost Redis that does not
     * exist, or worse, one that does.
     */
    @Test
    void redis_mode_refuses_to_start_without_an_explicit_endpoint() {
        ConsumerRateLimitProperties p = complete();
        p.setRedisUrl(null);
        assertThatThrownBy(p::requireCompleteForRedis)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis-url");

        ConsumerRateLimitProperties blank = complete();
        blank.setRedisUrl("   ");
        assertThatThrownBy(blank::requireCompleteForRedis).hasMessageContaining("redis-url");
    }

    /**
     * With no trusted proxy configured, the resolver would treat the ALB itself as the client and
     * every consumer would land in one bucket. Refusing to start is the only safe answer.
     */
    @Test
    void redis_mode_refuses_to_start_without_a_trusted_proxy_decision() {
        ConsumerRateLimitProperties p = complete();
        p.setTrustedProxyCidrs(List.of());
        assertThatThrownBy(p::requireCompleteForRedis)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxy-cidrs");
    }

    @Test
    void a_complete_redis_configuration_is_accepted() {
        assertThatCode(() -> complete().requireCompleteForRedis()).doesNotThrowAnyException();
        assertThat(complete().resolvedMode()).isEqualTo(ConsumerRateLimitProperties.Mode.REDIS);
    }
}
