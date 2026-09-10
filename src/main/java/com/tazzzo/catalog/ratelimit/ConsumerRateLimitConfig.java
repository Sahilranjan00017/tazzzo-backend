package com.tazzzo.catalog.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import io.lettuce.core.RedisURI;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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
    @Conditional(RedisModeCondition.class)
    static class RedisMode {

        /**
         * The limiter's OWN connection, built from {@code tazzzo.consumer-rate-limit.redis-url}.
         *
         * <p>Deliberately not an injected {@code StringRedisTemplate}: Spring Boot's Redis
         * autoconfiguration would happily hand over a localhost default, so a production deployment
         * that never configured a limiter endpoint would start and quietly admit against a Redis
         * that does not exist — or worse, one that does. {@code RedisAutoConfiguration} is excluded
         * from the application for the same reason.
         */
        @Bean
        public StringRedisTemplate consumerRateLimitRedisTemplate(ConsumerRateLimitProperties properties) {
            properties.requireCompleteForRedis();
            RedisURI uri;
            try {
                uri = RedisURI.create(properties.getRedisUrl().trim());
            } catch (RuntimeException e) {
                // NEITHER the value NOR the parser's exception may appear. The URL supports
                // username:password, so a typo in a credential-bearing endpoint would otherwise
                // put that credential into startup logs — and a nested cause can reproduce it just
                // as effectively as the message. The property name is enough to fix it.
                throw new IllegalStateException("tazzzo.consumer-rate-limit.redis-url is not a "
                        + "valid Redis URL (value withheld: it may carry credentials)");
            }
            RedisStandaloneConfiguration standalone =
                    new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
            if (uri.getPassword() != null && uri.getPassword().length > 0) {
                standalone.setPassword(RedisPassword.of(uri.getPassword()));
            }
            if (uri.getUsername() != null && !uri.getUsername().isBlank()) {
                standalone.setUsername(uri.getUsername());
            }
            LettuceClientConfiguration.LettuceClientConfigurationBuilder client =
                    LettuceClientConfiguration.builder();
            if (uri.isSsl()) {
                client.useSsl();
            }
            LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client.build());
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            return template;
        }

        @Bean
        public RateLimitStore rateLimitStore(StringRedisTemplate consumerRateLimitRedisTemplate) {
            return new RedisRateLimitStore(consumerRateLimitRedisTemplate);
        }

        @Bean
        public ConsumerRateLimiter consumerRateLimiter(ConsumerRateLimitProperties properties,
                                                       RateLimitStore store) {
            return new ConsumerRateLimiter(store, properties.getIp(), properties.getInstallation());
        }
    }
}
