package com.tazzzo.customer.cart;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** PR-12C — enables {@link CartLimitProperties}. */
@Configuration
@EnableConfigurationProperties(CartLimitProperties.class)
public class CustomerCartConfig {
}
