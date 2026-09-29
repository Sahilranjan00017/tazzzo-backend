package com.tazzzo.serviceability;

import com.mongodb.client.ClientSession;
import com.tazzzo.commerce.contract.Pincode;

/**
 * PR-14B — a companion to {@link ServiceabilityReadPort} for a caller whose read MUST participate
 * in the CALLER's own outer transaction (a future {@code customer.order}'s routing revalidation).
 * Deliberately a SEPARATE interface rather than a new abstract method on
 * {@link ServiceabilityReadPort} itself, which stays a plain single-abstract-method contract so
 * existing consumers/stubs keep compiling unchanged. {@link ServiceabilityService} implements
 * BOTH ports, sharing ONE resolution algorithm; only the Mongo call shape differs
 * ({@code find(...)} vs {@code find(session, ...)}).
 */
public interface TransactionalServiceabilityReadPort {

    /** Session-aware resolution, otherwise identical to {@link ServiceabilityReadPort#resolveByPincode}. */
    ServiceabilityResolution resolveByPincode(ClientSession session, Pincode pin);
}
