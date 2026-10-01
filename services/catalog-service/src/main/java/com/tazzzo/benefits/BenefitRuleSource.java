package com.tazzzo.benefits;

import java.util.Optional;

/** The Benefits rule lookup. EXACT {@code (planId, planVersion)} match only — never a fallback. */
public interface BenefitRuleSource {

    Optional<BenefitRule> find(String planId, int planVersion);
}
