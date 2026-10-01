package com.tazzzo.membership;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-16A-1 -- the Asia/Kolkata billing calendar. Expectations are LITERAL instants (not recomputed with the
 * code under test). Isolated because one test changes the JVM default zone (restored in {@code finally}).
 */
@Isolated
class MembershipBillingCalendarTest {

    /** {periodCount, periodMonths, activation instant, expected validUntil instant}, with the IST meaning. */
    private record Row(String name, long periodCount, int periodMonths, String from, String until) {
    }

    private static final List<Row> MATRIX = List.of(
            new Row("31 Jan 2027 10:00 IST -> 28 Feb 2027 10:00 IST (non-leap)", 1, 1,
                    "2027-01-31T04:30:00Z", "2027-02-28T04:30:00Z"),
            new Row("31 Jan 2028 10:00 IST -> 29 Feb 2028 10:00 IST (leap)", 1, 1,
                    "2028-01-31T04:30:00Z", "2028-02-29T04:30:00Z"),
            new Row("28 Feb 2027 -> 28 Mar 2027", 1, 1, "2027-02-28T04:30:00Z", "2027-03-28T04:30:00Z"),
            new Row("29 Feb 2028 -> 29 Mar 2028", 1, 1, "2028-02-29T04:30:00Z", "2028-03-29T04:30:00Z"),
            new Row("31 Mar 2027 -> 30 Apr 2027", 1, 1, "2027-03-31T04:30:00Z", "2027-04-30T04:30:00Z"),
            new Row("31 Dec 2027 -> 31 Jan 2028 (year rollover)", 1, 1,
                    "2027-12-31T04:30:00Z", "2028-01-31T04:30:00Z"),
            new Row("31 Jan 2027 00:00 IST -> 28 Feb 2027 00:00 IST (UTC date differs)", 1, 1,
                    "2027-01-30T18:30:00Z", "2027-02-27T18:30:00Z"),
            new Row("31 Jan 2027 00:30 IST -> 28 Feb 2027 00:30 IST (UTC date differs)", 1, 1,
                    "2027-01-30T19:00:00Z", "2027-02-27T19:00:00Z"),
            new Row("01 Mar 2027 00:30 IST -> 01 Apr 2027 00:30 IST", 1, 1,
                    "2027-02-28T19:00:00Z", "2027-03-31T19:00:00Z"),
            new Row("01 Mar 2027 01:30 IST -> 01 Apr 2027 01:30 IST", 1, 1,
                    "2027-02-28T20:00:00Z", "2027-03-31T20:00:00Z"),
            new Row("31 Jan 2027 periodCount=2 anchored -> 31 Mar 2027 (never 28 Mar)", 2, 1,
                    "2027-01-31T04:30:00Z", "2027-03-31T04:30:00Z"),
            new Row("29 Feb 2028 + 12 periods of 1 month -> 28 Feb 2029", 12, 1,
                    "2028-02-29T04:30:00Z", "2029-02-28T04:30:00Z"),
            new Row("29 Feb 2028 + 1 period of 12 months -> 28 Feb 2029", 1, 12,
                    "2028-02-29T04:30:00Z", "2029-02-28T04:30:00Z"));

    @Test
    void every_matrix_row_produces_the_literal_expected_window() {
        for (Row r : MATRIX) {
            assertThat(MembershipBillingCalendar.validUntil(Instant.parse(r.from()), r.periodCount(), r.periodMonths()))
                    .as(r.name()).isEqualTo(Instant.parse(r.until()));
        }
    }

    @Test
    void the_rejected_utc_rule_would_have_produced_a_different_and_wrong_window() {
        // 2027-03-01 01:30 IST = 2027-02-28T20:00Z. UTC + 1 month = 2027-03-28T20:00Z = 29 Mar 01:30 IST.
        Instant from = Instant.parse("2027-02-28T20:00:00Z");
        Instant utcRule = ZonedDateTime.ofInstant(from, ZoneOffset.UTC).plusMonths(1).toInstant();
        Instant billing = MembershipBillingCalendar.validUntil(from, 1, 1);
        assertThat(utcRule).isEqualTo(Instant.parse("2027-03-28T20:00:00Z"));
        assertThat(billing).isEqualTo(Instant.parse("2027-03-31T20:00:00Z")).isNotEqualTo(utcRule);
    }

    @Test
    void the_same_input_yields_the_same_instants_under_every_jvm_default_zone() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Kolkata", "America/New_York", "Pacific/Kiritimati",
                    "Pacific/Pago_Pago")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(ZoneId.systemDefault().getId()).as("default zone really changed").isEqualTo(zone);
                for (Row r : MATRIX) {
                    assertThat(MembershipBillingCalendar.validUntil(Instant.parse(r.from()), r.periodCount(),
                            r.periodMonths())).as(zone + ": " + r.name()).isEqualTo(Instant.parse(r.until()));
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void a_sub_millisecond_activation_is_truncated_to_milliseconds_first() {
        Instant from = Instant.parse("2027-02-28T20:00:00.123456789Z");
        assertThat(MembershipBillingCalendar.validUntil(from, 1, 1))
                .isEqualTo(Instant.parse("2027-03-31T20:00:00.123Z"));
        assertThat(MembershipBillingCalendar.truncate(from)).isEqualTo(Instant.parse("2027-02-28T20:00:00.123Z"));
    }

    @Test
    void multiplication_overflow_fails_loud() {
        assertThatThrownBy(() -> MembershipBillingCalendar.validUntil(Instant.parse("2027-01-31T04:30:00Z"),
                Long.MAX_VALUE, 2)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void date_time_overflow_fails_loud() {
        // 1.2e10 months fits in a long but pushes the year far past the supported range
        assertThatThrownBy(() -> MembershipBillingCalendar.validUntil(Instant.parse("2027-01-31T04:30:00Z"),
                1_000_000_000L, 12)).isInstanceOf(DateTimeException.class);
    }

    @Test
    void invalid_period_inputs_are_rejected() {
        Instant from = Instant.parse("2027-01-31T04:30:00Z");
        assertThatThrownBy(() -> MembershipBillingCalendar.validUntil(from, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MembershipBillingCalendar.validUntil(from, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MembershipBillingCalendar.validUntil(from, 1, 121))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MembershipBillingCalendar.validUntil(null, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_billing_zone_is_pinned_and_validated() {
        assertThat(MembershipBillingCalendar.BILLING_ZONE_ID).isEqualTo("Asia/Kolkata");
        MembershipBillingCalendar.requireSupportedZone(); // the real zone passes
        assertThatThrownBy(() -> MembershipBillingCalendar.requireSupportedZone(ZoneId.of("UTC")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MembershipBillingCalendar.requireSupportedZone(ZoneId.of("America/New_York")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MembershipBillingCalendar.requireSupportedZone(ZoneOffset.ofHoursMinutes(5, 30)))
                .isInstanceOf(IllegalStateException.class);
    }
}
