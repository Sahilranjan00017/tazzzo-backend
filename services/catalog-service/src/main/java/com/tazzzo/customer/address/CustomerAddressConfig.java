package com.tazzzo.customer.address;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** PR-12B — enables {@link AddressLimitProperties} (mirrors {@code CustomerSessionConfig}'s
 *  wiring convention). */
@Configuration
@EnableConfigurationProperties(AddressLimitProperties.class)
public class CustomerAddressConfig {
}
