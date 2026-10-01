package com.tazzzo.membership;

import com.tazzzo.common.money.Currency;
import com.tazzzo.common.money.Money;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * PR-16A-1 — one immutable, version-explicit commercial plan: stable {@code planId}, a version, the
 * subscription purchase price (int64 paise, INR) and the billing period in calendar months. Carries
 * NO presentation (CMS owns it, ADR-012) and NO benefit semantics (discount percentages, thresholds,
 * coupons belong to a later Benefits domain). {@code effectiveFrom}/{@code effectiveUntil} gate NEW
 * grants only, as a half-open window.
 */
public record MembershipPlan(String planId, int version, Money price, int periodMonths,
                             Instant effectiveFrom, Instant effectiveUntil) {

    public static final Pattern PLAN_ID = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    public MembershipPlan {
        if (planId == null || !PLAN_ID.matcher(planId).matches()) {
            throw new IllegalArgumentException("invalid plan id shape");
        }
        if (version < 1) {
            throw new IllegalArgumentException("plan version must be >= 1");
        }
        if (price == null || price.paise() <= 0) {
            throw new IllegalArgumentException("plan price must be > 0 paise");
        }
        if (price.currency() != Currency.INR) {
            throw new IllegalArgumentException("plan currency must be INR");
        }
        if (periodMonths < MembershipBillingCalendar.MIN_PERIOD_MONTHS
                || periodMonths > MembershipBillingCalendar.MAX_PERIOD_MONTHS) {
            throw new IllegalArgumentException("plan periodMonths must be within "
                    + MembershipBillingCalendar.MIN_PERIOD_MONTHS + ".." + MembershipBillingCalendar.MAX_PERIOD_MONTHS);
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("plan effectiveFrom required");
        }
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("plan effectiveUntil must be after effectiveFrom");
        }
    }

    /** {@code effectiveFrom <= now AND (effectiveUntil == null OR now < effectiveUntil)}. */
    public boolean isEffectiveAt(Instant now) {
        return !now.isBefore(effectiveFrom) && (effectiveUntil == null || now.isBefore(effectiveUntil));
    }
}
