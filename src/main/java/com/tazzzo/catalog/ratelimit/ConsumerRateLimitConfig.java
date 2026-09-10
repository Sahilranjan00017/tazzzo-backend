package com.tazzzo.catalog.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the Q5 limiter, and REFUSES TO START on incomplete configuration.
 *
 * <p>{@link ConsumerRateLimitProperties#resolvedMode()} throws when the mode is absent or
 * unrecognised, and it is called while creating the {@code consumerRateLimitMode} bean — so a
 * deployment that forgot the limiter fails loudly at startup instead of serving the public surface
 * unlimited. A permissive default would be worse than none, because it would look like a decision.
 *
 * <p>In {@code DISABLED} mode NO store and NO limiter bean exists at all. That is the intentional
 * fail-closed state: it does not mean "unlimited", it means the consumer surface must not be
 * exposed (Q4-d), and a later phase that wires a filter will find no limiter to call rather than
 * finding a permissive one.
 */
@Configuration
@EnableConfigurationProperties(ConsumerRateLimitProperties.class)
public class ConsumerRateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsumerRateLimitConfig.class);

    /** Always created: this is where a missing or invalid mode becomes a startup failure. */
    @Bean
    public ConsumerRateLimitProperties.Mode consumerRateLimitMode(ConsumerRateLimitProperties properties) {
        ConsumerRateLimitProperties.Mode mode = properties.resolvedMode();
        if (mode == ConsumerRateLimitProperties.Mode.DISABLED) {
            log.info("consumer rate limiting DISABLED (fail-closed): no limiter is constructed, "
                    + "and the consumer surface must not be exposed");
        }
        return mode;
    }

    /**
     * Always created: resolving a client address is not itself rate limiting, and a malformed CIDR
     * should fail at startup rather than on the first request.
     */
    @Bean
    public ClientIpResolver clientIpResolver(ConsumerRateLimitProperties properties) {
        return new ClientIpResolver(properties.getTrustedProxyCidrs());
    }

    @Configuration
    @ConditionalOnProperty(name = "tazzzo.consumer-rate-limit.mode", havingValue = "REDIS")
    static class RedisMode {

        @Bean
        public RateLimitStore rateLimitStore(ConsumerRateLimitProperties properties,
                                             StringRedisTemplate redis) {
            properties.requireCompleteForRedis();
            return new RedisRateLimitStore(redis);
        }

        @Bean
        public ConsumerRateLimiter consumerRateLimiter(ConsumerRateLimitProperties properties,
                                                       RateLimitStore store) {
            return new ConsumerRateLimiter(store, properties.getIp(), properties.getInstallation());
        }
    }
}
