package com.tazzzo.catalog.ratelimit;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers the Redis limiter beans iff the mode is REDIS — decided by
 * {@link ConsumerRateLimitProperties#resolvedMode()}, the SAME parser that validates the property.
 *
 * <p><b>Why not {@code @ConditionalOnProperty}.</b> That would put a SECOND parser in charge of
 * whether the limiter exists, and it does not agree with the first: Spring's condition compares
 * case-INSENSITIVELY, so {@code mode=redis} would satisfy it while {@code resolvedMode()} rejects
 * the spelling. Two parsers deciding whether a security control exists is the split itself; this
 * removes it rather than tuning around it.
 *
 * <p>A non-canonical value throws here, during configuration parsing, so the context fails with the
 * same message it would fail with later — there is one rule and one error.
 */
public class RedisModeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConsumerRateLimitProperties properties = Binder.get(context.getEnvironment())
                .bind("tazzzo.consumer-rate-limit", ConsumerRateLimitProperties.class)
                .orElseGet(ConsumerRateLimitProperties::new);
        return properties.resolvedMode() == ConsumerRateLimitProperties.Mode.REDIS;
    }
}
