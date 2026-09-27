package com.tazzzo.auth.session;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** PR-11C — enables {@link CustomerSessionProperties} (mirrors {@code OtpAuthConfig}'s wiring). */
@Configuration
@EnableConfigurationProperties(CustomerSessionProperties.class)
public class CustomerSessionConfig {
}
