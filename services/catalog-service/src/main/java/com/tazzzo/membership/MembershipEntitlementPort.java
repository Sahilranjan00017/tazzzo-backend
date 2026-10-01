package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;

import java.util.Optional;

/**
 * PR-16A-2 — the standalone (non-transactional) entitlement read for a consumer that is NOT inside its own
 * transaction. Read only: it mutates nothing, persists no lazy expiry, opens no transaction.
 *
 * <p><b>Time authority.</b> Membership owns "now": there is deliberately no caller-supplied instant (a
 * caller's possibly-stale time could grant an expired term), the injected {@code Clock} is read fresh on every
 * call. The runtime truth is {@code status == ACTIVE AND validFrom <= now < validUntil} (half-open).
 *
 * <p><b>Empty means "authoritatively no entitlement"</b> — never "could not determine". An outage or a corrupt
 * persisted row throws {@link MembershipFailure} ({@code UNAVAILABLE} / {@code INTEGRITY_FAILURE}); the consumer
 * owns its fail-open/fail-closed policy. A persisted {@code ACTIVE} row whose window has ended (valid stale
 * state) yields empty.
 *
 * <p>A caller already inside a {@code Tx.call} must use {@link TransactionalMembershipEntitlementPort}.
 */
public interface MembershipEntitlementPort {

    Optional<MembershipEntitlement> currentEntitlement(CustomerId customerId);
}
