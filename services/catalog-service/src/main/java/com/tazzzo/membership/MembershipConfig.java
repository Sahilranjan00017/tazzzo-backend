package com.tazzzo.membership;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PR-16A-1 — wires the immutable config-backed plan source and validates the billing zone. Both fail
 * the application start rather than surfacing at runtime.
 */
@Configuration
@EnableConfigurationProperties(MembershipPlanProperties.class)
public class MembershipConfig {

    @PostConstruct
    void validateBillingZone() {
        MembershipBillingCalendar.requireSupportedZone();
    }

    @Bean
    public MembershipPlanSource membershipPlanSource(MembershipPlanProperties properties) {
        return new ConfigBackedMembershipPlanSource(properties.toPlans());
    }
}
