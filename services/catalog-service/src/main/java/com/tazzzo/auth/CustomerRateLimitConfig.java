package com.tazzzo.auth;

import com.tazzzo.catalog.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires {@link CustomerRateLimitFilter} AFTER every authentication filter. Unset budgets = not enforced (logged at startup);
 * budgets set without a rate-limit store (consumer mode not REDIS) = startup failure, never a silently unlimited surface.
 */
@Configuration
@EnableConfigurationProperties(CustomerRateLimitProperties.class)
class CustomerRateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomerRateLimitConfig.class);
    /** After RequestId, body limit, CORS, ApiAuth and CustomerAuth, whatever their exact slots. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Bean
    FilterRegistrationBean<CustomerRateLimitFilter> customerRateLimitFilter(CustomerRateLimitProperties properties,
                                                                            ObjectProvider<RateLimitStore> store,
                                                                            ObjectProvider<MeterRegistry> registry) {
        FilterRegistrationBean<CustomerRateLimitFilter> reg = new FilterRegistrationBean<>();
        if (!properties.resolveEnabled()) {
            log.warn("customer_rate_limit_not_enforced reason=budgets_unset (set tazzzo.customer-rate-limit.* before exposing /v1/customer)");
            reg.setEnabled(false);
            reg.setFilter(new CustomerRateLimitFilter(null, properties, null));
            return reg;
        }
        RateLimitStore s = store.getIfAvailable();
        if (s == null) {
            throw new IllegalStateException("tazzzo.customer-rate-limit is configured but no rate-limit store exists "
                    + "(tazzzo.consumer-rate-limit.mode must be REDIS)");
        }
        reg.setFilter(new CustomerRateLimitFilter(s, properties,
                registry.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)));
        reg.addUrlPatterns("/v1/customer/*");
        reg.setOrder(ORDER);
        return reg;
    }
}
