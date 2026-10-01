package com.tazzzo.membership;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;

import java.util.Optional;

/**
 * PR-16A-2 — the session-aware companion of {@link MembershipEntitlementPort} for a caller whose read MUST
 * participate in the CALLER's own outer transaction (a future Checkout/Order money validation), the same
 * separate-interface shape as {@code TransactionalPriceReadPort}.
 *
 * <p><b>CRITICAL — the contract of every implementation:</b>
 * <ul>
 *   <li>it joins the caller's {@code session} and opens NO transaction of its own;</li>
 *   <li>it performs NO mutation (a stale {@code ACTIVE} row is not expired here);</li>
 *   <li>it emits ZERO metrics — success, failure, entitled/not-entitled, transition — because the caller's
 *       {@code Tx.call} body may be retried and this port can never know whether the caller commits; the
 *       operation that owns the transaction owns observability;</li>
 *   <li>it reads the Membership {@code Clock} fresh on every call and never accepts a caller-supplied time;</li>
 *   <li>it never falls back to a standalone (non-session) read.</li>
 * </ul>
 *
 * <p><b>Failure classification.</b> A transient transaction error (label {@code TransientTransactionError})
 * propagates UNTOUCHED so the caller's {@code Tx.call} retry keeps working; any other datastore failure is a
 * typed {@code MembershipFailure(UNAVAILABLE)}, a corrupt row {@code INTEGRITY_FAILURE}. Neither ever collapses
 * to an empty result: empty means authoritatively no entitlement.
 */
public interface TransactionalMembershipEntitlementPort {

    Optional<MembershipEntitlement> currentEntitlement(ClientSession session, CustomerId customerId);
}
