package com.tazzzo.membership;

import java.util.Optional;

/**
 * PR-16A-1 — version-explicit plan lookup. The service depends on THIS port only, never on Spring
 * configuration classes, so a future insert-only database-backed source replaces the config-backed
 * one without changing {@code MembershipService}. Plan versions are immutable once published, so the
 * lookup is deliberately non-session: it is resolved before the grant transaction.
 */
public interface MembershipPlanSource {

    Optional<MembershipPlan> find(String planId, int version);
}
