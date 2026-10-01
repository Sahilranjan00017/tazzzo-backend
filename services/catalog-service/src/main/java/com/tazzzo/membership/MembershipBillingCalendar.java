package com.tazzzo.membership;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * PR-16A-1 — the ONE authoritative Membership calendar. A monthly period is a calendar month in the
 * Membership billing zone, {@value #BILLING_ZONE_ID} for the India launch. This is domain
 * configuration owned here as a constant (it is part of the persisted-data contract: strict
 * reconstruction recomputes {@code validUntil} with it), NOT the JVM's machine zone — the same input
 * yields the same {@link Instant} whatever {@code user.timezone} the JVM runs with.
 *
 * <p>Month-end behaviour is Java's calendar clamping (31 Jan + 1 month = 28/29 Feb), preserving the
 * local time of day. Periods are ANCHORED: the end is always computed from the original activation
 * plus the total month count, never by repeatedly adding one month to the previous end (which would
 * drift 31 Jan -> 28 Feb -> 28 Mar instead of 31 Mar).
 *
 * <p>Only this class may perform Membership calendar arithmetic ({@code ModuleBoundaryTest}).
 */
public final class MembershipBillingCalendar {

    public static final String BILLING_ZONE_ID = "Asia/Kolkata";
    public static final int MIN_PERIOD_MONTHS = 1;
    public static final int MAX_PERIOD_MONTHS = 120;

    private static final ZoneId BILLING_ZONE = ZoneId.of(BILLING_ZONE_ID);
    private static final ZoneOffset EXPECTED_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);

    private MembershipBillingCalendar() {
    }

    /** Membership timestamps are persisted as Mongo dates, i.e. millisecond precision. */
    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * The end of the window that starts at {@code validFrom} and spans {@code periodCount} periods of
     * {@code periodMonths} calendar months. Steps: truncate to milliseconds; exact
     * {@code periodCount * periodMonths}; convert to the billing zone; add the total months; convert
     * back. {@link ArithmeticException} (multiplication overflow) and
     * {@link java.time.DateTimeException} (date-time overflow) propagate — never clamped, never
     * defaulted; callers map them to an integrity failure.
     */
    public static Instant validUntil(Instant validFrom, long periodCount, int periodMonths) {
        if (validFrom == null) {
            throw new IllegalArgumentException("validFrom required");
        }
        if (periodCount < 1) {
            throw new IllegalArgumentException("periodCount must be >= 1");
        }
        if (periodMonths < MIN_PERIOD_MONTHS || periodMonths > MAX_PERIOD_MONTHS) {
            throw new IllegalArgumentException(
                    "periodMonths must be within " + MIN_PERIOD_MONTHS + ".." + MAX_PERIOD_MONTHS);
        }
        Instant anchor = truncate(validFrom);
        long totalMonths = Math.multiplyExact(periodCount, (long) periodMonths);
        return ZonedDateTime.ofInstant(anchor, BILLING_ZONE).plusMonths(totalMonths).toInstant();
    }

    /** Startup validation: the billing zone resolves in THIS JVM, is +05:30 and has no recurring
     *  daylight-saving rules (so every local time is unambiguous). Fails the application start. */
    public static void requireSupportedZone() {
        requireSupportedZone(BILLING_ZONE);
    }

    static void requireSupportedZone(ZoneId zone) {
        if (!BILLING_ZONE_ID.equals(zone.getId())) {
            throw new IllegalStateException("membership billing zone must be " + BILLING_ZONE_ID);
        }
        if (!zone.getRules().getTransitionRules().isEmpty()) {
            throw new IllegalStateException("membership billing zone must have no recurring DST rules");
        }
        if (!EXPECTED_OFFSET.equals(zone.getRules().getOffset(Instant.parse("2026-01-01T00:00:00Z")))) {
            throw new IllegalStateException("membership billing zone offset must be +05:30");
        }
    }
}
