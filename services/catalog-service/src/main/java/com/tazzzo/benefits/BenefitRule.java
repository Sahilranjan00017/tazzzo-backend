package com.tazzzo.benefits;

import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;

import java.util.regex.Pattern;

/**
 * One immutable Benefits rule: an ORDER-LEVEL percentage discount with an inclusive minimum eligible subtotal,
 * keyed by the EXACT Membership plan snapshot {@code (planId, planVersion)} it applies to.
 *
 * <p>The key is exact because a {@code MembershipEntitlement} reports the persisted term snapshot, not the live
 * plan configuration: a rule for version 2 must never touch a version-1 entitlement, and there is no
 * latest-version, plan-id-only or default fallback. A zero-bps rule is a meaningless promotion and is rejected,
 * as is a zero minimum (an eligible subtotal is always at least one paise).
 */
public record BenefitRule(String planId, int planVersion, Money minimumSubtotal, DiscountBps discountBps) {

    /** Same shape the Membership plan ids use; duplicated here because Benefits may not depend on Membership plans. */
    public static final Pattern PLAN_ID = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    public BenefitRule {
        if (planId == null || !PLAN_ID.matcher(planId).matches()) {
            throw new IllegalArgumentException("invalid plan id shape");
        }
        if (planVersion < 1) {
            throw new IllegalArgumentException("plan version must be >= 1");
        }
        if (minimumSubtotal == null || minimumSubtotal.paise() < 1) {
            throw new IllegalArgumentException("minimum subtotal must be > 0 paise");
        }
        if (minimumSubtotal.currency() != Currency.INR) {
            throw new IllegalArgumentException("benefit rule currency must be INR");
        }
        if (discountBps == null || discountBps.bps() < 1) {
            throw new IllegalArgumentException("discount bps must be >= 1");
        }
    }

    /** Inclusive threshold: {@code subtotal >= minimumSubtotal}. */
    boolean admits(Money subtotal) {
        return subtotal.currency() == minimumSubtotal.currency() && subtotal.paise() >= minimumSubtotal.paise();
    }
}
