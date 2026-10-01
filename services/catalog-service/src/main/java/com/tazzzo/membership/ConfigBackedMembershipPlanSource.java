package com.tazzzo.membership;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PR-16A-1 — the immutable launch plan source: validates the whole configured plan set at
 * construction (so the application fails to START on bad configuration rather than discovering it at
 * runtime), loads once, and never refreshes. A plan version's commercial terms never change; a new
 * price is a new version.
 *
 * <p>Version effectiveness rules per {@code planId}: versions are ordered by version number; every
 * version except the last must have a finite {@code effectiveUntil}; and each version's
 * {@code effectiveUntil} must not extend past the next version's {@code effectiveFrom}
 * (non-overlapping, ascending).
 */
public final class ConfigBackedMembershipPlanSource implements MembershipPlanSource {

    private record Key(String planId, int version) {
    }

    private final Map<Key, MembershipPlan> plans;

    public ConfigBackedMembershipPlanSource(List<MembershipPlan> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("tazzzo.membership.plans must configure at least one plan");
        }
        Map<Key, MembershipPlan> byKey = new HashMap<>();
        Map<String, List<MembershipPlan>> byPlan = new HashMap<>();
        for (MembershipPlan plan : configured) {
            if (byKey.put(new Key(plan.planId(), plan.version()), plan) != null) {
                throw new IllegalStateException(
                        "duplicate membership plan " + plan.planId() + " version " + plan.version());
            }
            byPlan.computeIfAbsent(plan.planId(), k -> new ArrayList<>()).add(plan);
        }
        for (List<MembershipPlan> versions : byPlan.values()) {
            versions.sort(Comparator.comparingInt(MembershipPlan::version));
            for (int i = 0; i < versions.size() - 1; i++) {
                MembershipPlan current = versions.get(i);
                MembershipPlan next = versions.get(i + 1);
                if (current.effectiveUntil() == null) {
                    throw new IllegalStateException("membership plan " + current.planId() + " version "
                            + current.version() + " is open-ended but is not the final version");
                }
                if (current.effectiveUntil().isAfter(next.effectiveFrom())) {
                    throw new IllegalStateException("membership plan " + current.planId() + " versions "
                            + current.version() + " and " + next.version()
                            + " have overlapping or out-of-order effective windows");
                }
            }
        }
        this.plans = Map.copyOf(byKey);
    }

    @Override
    public Optional<MembershipPlan> find(String planId, int version) {
        return Optional.ofNullable(plans.get(new Key(planId, version)));
    }
}
