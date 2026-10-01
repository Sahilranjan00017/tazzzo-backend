package com.tazzzo.benefits;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the immutable config-backed rule source; invalid configuration fails the application start. */
@Configuration
@EnableConfigurationProperties(BenefitsRuleProperties.class)
public class BenefitsConfig {

    @Bean
    public BenefitRuleSource benefitRuleSource(BenefitsRuleProperties properties) {
        return new ConfigBackedBenefitRuleSource(properties.toRules());
    }
}
