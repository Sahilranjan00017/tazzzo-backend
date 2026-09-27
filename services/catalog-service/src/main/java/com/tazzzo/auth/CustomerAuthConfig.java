package com.tazzzo.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * PR-11A — supplies the injected {@link Clock} {@link CustomerAccessTokenCodec} uses for every
 * time decision (never {@code Instant.now()} directly), so token time-validity is deterministically
 * testable. No other {@code Clock} bean exists in this application yet. Also enables
 * {@link CustomerAuthProperties} (mirrors {@code ConsumerCursorProperties}'s wiring).
 */
@Configuration
@EnableConfigurationProperties(CustomerAuthProperties.class)
public class CustomerAuthConfig {

    @Bean
    public Clock customerAuthClock() {
        return Clock.systemUTC();
    }
}
