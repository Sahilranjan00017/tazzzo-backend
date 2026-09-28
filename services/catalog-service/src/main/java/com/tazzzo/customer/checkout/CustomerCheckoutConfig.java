package com.tazzzo.customer.checkout;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** PR-13A — enables {@link CheckoutProperties}. */
@Configuration
@EnableConfigurationProperties(CheckoutProperties.class)
public class CustomerCheckoutConfig {
}
