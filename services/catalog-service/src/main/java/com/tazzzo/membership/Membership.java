package com.tazzzo.membership;

import com.tazzzo.auth.CustomerId;
import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;

import java.time.Instant;

/**
 * PR-16A-1 — one Membership TERM: a customer's entitlement window under ONE immutable commercial plan
 * version. The plan facts ({@code planId}, {@code planVersion}, {@code planPrice},
 * {@code planPeriodMonths}) are a SNAPSHOT: a term never silently changes commercial terms, and a later
 * edit to the plan source cannot reinterpret history. {@code planPrice} is the plan's LIST price at
 * grant — it is NOT an amount collected; Membership carries no payment facts.
 *
 * <p>The window is half-open {@code [validFrom, validUntil)}. <b>Persisted state is not runtime
 * entitlement truth</b>: an {@code ACTIVE} row whose {@code validUntil} has passed is valid stale state
 * (non-entitling), and one whose {@code validFrom} is in the future (multi-node clock skew) is
 * non-entitling yet still holds the customer's open slot; {@code EXPIRED} is terminal. Construction
 * enforces every structural invariant, including that {@code validUntil} equals
 * {@link MembershipBillingCalendar#validUntil} — so a reconstructed row can never disagree with the
 * one billing-calendar formula.
 *
 * <p><b>PR-16A-3 termination facts</b> (both nullable, absent on every pre-existing row, never backfilled):
 * {@code cancelRequestedAt} records a cancel-at-period-end request on a term — it changes neither the status nor
 * {@code validUntil}, entitlement continues until the window ends; {@code revokedAt} is present if and only if the
 * status is {@code REVOKED}, the immediate administrative termination (non-entitling at once; a prior
 * {@code cancelRequestedAt} is preserved as history). Both come from the service clock and are written once.
 */
public record Membership(MembershipId membershipId, CustomerId customerId, MembershipStatus status, long version,
                         MembershipGrantReference grantReference, String planId, int planVersion,
                         Money planPrice, int planPeriodMonths, String billingZoneId, long periodCount,
                         Instant validFrom, Instant validUntil, Instant createdAt, Instant updatedAt,
                         Instant cancelRequestedAt, Instant revokedAt) {

    public Membership {
        if (membershipId == null || customerId == null || status == null || grantReference == null
                || planId == null || planPrice == null || billingZoneId == null || validFrom == null
                || validUntil == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("membership field missing");
        }
        if (version < 1) {
            throw new IllegalArgumentException("membership version must be >= 1");
        }
        if (!MembershipPlan.PLAN_ID.matcher(planId).matches()) {
            throw new IllegalArgumentException("invalid plan id shape");
        }
        if (planVersion < 1) {
            throw new IllegalArgumentException("plan version must be >= 1");
        }
        if (planPrice.paise() <= 0 || planPrice.currency() != Currency.INR) {
            throw new IllegalArgumentException("plan price must be > 0 paise INR");
        }
        if (planPeriodMonths < MembershipBillingCalendar.MIN_PERIOD_MONTHS
                || planPeriodMonths > MembershipBillingCalendar.MAX_PERIOD_MONTHS) {
            throw new IllegalArgumentException("plan period months out of range");
        }
        if (!MembershipBillingCalendar.BILLING_ZONE_ID.equals(billingZoneId)) {
            throw new IllegalArgumentException("unsupported billing zone");
        }
        if (periodCount < 1) {
            throw new IllegalArgumentException("periodCount must be >= 1");
        }
        requireMillis(validFrom);
        requireMillis(validUntil);
        requireMillis(createdAt);
        requireMillis(updatedAt);
        if (!validFrom.isBefore(validUntil)) {
            throw new IllegalArgumentException("validFrom must be before validUntil");
        }
        if (!createdAt.equals(validFrom)) {
            throw new IllegalArgumentException("a term is born ACTIVE at activation: createdAt must equal validFrom");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if (!validUntil.equals(MembershipBillingCalendar.validUntil(validFrom, periodCount, planPeriodMonths))) {
            throw new IllegalArgumentException("validUntil does not match the billing-calendar formula");
        }
        if (cancelRequestedAt != null) {
            requireMillis(cancelRequestedAt);
            // a cancel is only ever recorded on a started, not-yet-ended window
            if (cancelRequestedAt.isBefore(validFrom) || !cancelRequestedAt.isBefore(validUntil)) {
                throw new IllegalArgumentException("cancelRequestedAt must lie within [validFrom, validUntil)");
            }
        }
        if ((revokedAt != null) != (status == MembershipStatus.REVOKED)) {
            throw new IllegalArgumentException("revokedAt must be present if and only if the status is REVOKED");
        }
        if (revokedAt != null) {
            requireMillis(revokedAt);
            // a revoke is only ever recorded on a started, not-yet-ended window, as the one mutation that moved it
            if (revokedAt.isBefore(validFrom) || !revokedAt.isBefore(validUntil)) {
                throw new IllegalArgumentException("revokedAt must lie within [validFrom, validUntil)");
            }
            if (!updatedAt.equals(revokedAt)) {
                throw new IllegalArgumentException("a REVOKED term was last updated by its revoke");
            }
            if (version < 2) {
                throw new IllegalArgumentException("a REVOKED term must have transitioned at least once");
            }
        }
        if (status == MembershipStatus.ACTIVE && cancelRequestedAt != null) {
            if (version < 2) {
                throw new IllegalArgumentException("a cancel request advances the version");
            }
            if (!updatedAt.equals(cancelRequestedAt)) {
                throw new IllegalArgumentException("an ACTIVE term with a cancel request was last updated by it");
            }
        }
        if (status == MembershipStatus.EXPIRED) {
            // expiry is only ever persisted at/after the window end, by a version-incrementing CAS
            if (version < 2) {
                throw new IllegalArgumentException("an EXPIRED term must have transitioned at least once");
            }
            if (updatedAt.isBefore(validUntil)) {
                throw new IllegalArgumentException("EXPIRED persisted before the window ended");
            }
        }
    }

    private static void requireMillis(Instant instant) {
        if (!instant.equals(MembershipBillingCalendar.truncate(instant))) {
            throw new IllegalArgumentException("membership timestamps are millisecond precision");
        }
    }

    /** The grant: ACTIVE, version 1, one period, anchored at {@code now} (truncated to milliseconds). */
    static Membership newGrant(MembershipId id, CustomerId customerId, MembershipGrantReference reference,
                               MembershipPlan plan, Instant now) {
        Instant validFrom = MembershipBillingCalendar.truncate(now);
        Instant validUntil = MembershipBillingCalendar.validUntil(validFrom, 1L, plan.periodMonths());
        return new Membership(id, customerId, MembershipStatus.ACTIVE, 1L, reference, plan.planId(),
                plan.version(), plan.price(), plan.periodMonths(), MembershipBillingCalendar.BILLING_ZONE_ID, 1L,
                validFrom, validUntil, validFrom, validFrom, null, null);
    }

    /**
     * Runtime entitlement truth: {@code status == ACTIVE AND validFrom <= now < validUntil}, half-open. The
     * persisted status alone is NOT enough — a stale ACTIVE row (window ended) and a not-yet-started one (clock
     * skew) are both non-entitling.
     */
    public boolean isEntitlingAt(Instant now) {
        return status == MembershipStatus.ACTIVE && !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    /** {@code now >= validUntil}: the window has ended, whatever the persisted status says. */
    public boolean windowEndedAt(Instant now) {
        return !now.isBefore(validUntil);
    }

    /** Replay equality: the SEMANTIC input of a grant (never mutable lifecycle state). */
    boolean matchesGrantInput(CustomerId otherCustomer, String otherPlanId, int otherPlanVersion) {
        return customerId.equals(otherCustomer) && planId.equals(otherPlanId) && planVersion == otherPlanVersion;
    }
}
