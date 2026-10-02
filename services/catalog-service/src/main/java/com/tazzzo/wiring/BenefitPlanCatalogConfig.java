package com.tazzzo.wiring;

import com.tazzzo.benefits.BenefitPlanCatalog;
import com.tazzzo.membership.MembershipPlanSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition layer: the ONE place that connects the two static configuration models. It answers Benefits'
 * "is this plan version configured?" from the already-loaded Membership plan CONFIGURATION ({@link MembershipPlanSource}):
 * no Mongo read, no entitlement lookup, no customer data. Kept out of both domains on purpose so that Benefits does not
 * depend on any Membership implementation class (and Membership never depends on Benefits).
 */
@Configuration
public class BenefitPlanCatalogConfig {

    @Bean
    public BenefitPlanCatalog benefitPlanCatalog(MembershipPlanSource plans) {
        return (planId, planVersion) -> plans.find(planId, planVersion).isPresent();
    }
}
