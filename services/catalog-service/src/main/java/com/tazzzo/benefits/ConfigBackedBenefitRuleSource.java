package com.tazzzo.benefits;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable in-process rule source: validates the whole configured set at construction (so the application
 * fails to START on bad configuration), loads once and never refreshes. Unlike the Membership plan source it may
 * be EMPTY — no launch percentage or threshold is ratified, so the production default configures no rule and every
 * entitled customer resolves to "no rule".
 */
public final class ConfigBackedBenefitRuleSource implements BenefitRuleSource {

    private record Key(String planId, int version) {
    }

    private final Map<Key, BenefitRule> rules;

    public ConfigBackedBenefitRuleSource(List<BenefitRule> configured) {
        if (configured == null) {
            throw new IllegalStateException("benefit rules must not be null");
        }
        Map<Key, BenefitRule> byKey = new HashMap<>();
        for (BenefitRule rule : configured) {
            if (rule == null) {
                throw new IllegalStateException("benefit rule must not be null");
            }
            if (byKey.put(new Key(rule.planId(), rule.planVersion()), rule) != null) {
                throw new IllegalStateException(
                        "duplicate benefit rule for plan " + rule.planId() + " version " + rule.planVersion());
            }
        }
        this.rules = Map.copyOf(byKey);
    }

    @Override
    public Optional<BenefitRule> find(String planId, int planVersion) {
        return Optional.ofNullable(rules.get(new Key(planId, planVersion)));
    }
}
