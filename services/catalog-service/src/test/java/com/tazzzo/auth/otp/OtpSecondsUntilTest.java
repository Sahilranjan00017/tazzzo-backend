package com.tazzzo.auth.otp;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Mongo truncates dates to ms, so the stored instant is up to 1 ms before sentAt + configured; the response must still report the configured seconds. */
class OtpSecondsUntilTest {

    private static final Instant SENT = Instant.parse("2026-06-01T00:00:00.123456789Z");

    private static Instant mongoStored(Instant i) {
        return i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    @Test
    void the_configured_ttl_and_cooldown_are_reported_exactly_despite_millisecond_truncation() {
        assertThat(OtpService.secondsUntil(SENT, mongoStored(SENT.plusSeconds(300)))).isEqualTo(300);
        assertThat(OtpService.secondsUntil(SENT, mongoStored(SENT.plusSeconds(30)))).isEqualTo(30);
        assertThat(OtpService.secondsUntil(SENT, mongoStored(SENT.plusSeconds(2)))).isEqualTo(2);
    }

    @Test
    void a_zero_cooldown_is_zero_never_negative() {
        assertThat(OtpService.secondsUntil(SENT, mongoStored(SENT))).isZero();
        assertThat(OtpService.secondsUntil(SENT, SENT.minusMillis(5))).isZero();
    }

    @Test
    void an_already_ms_aligned_instant_is_unchanged() {
        Instant aligned = Instant.parse("2026-06-01T00:00:00Z");
        assertThat(OtpService.secondsUntil(aligned, aligned.plusSeconds(300))).isEqualTo(300);
        assertThat(OtpService.secondsUntil(aligned, aligned.plusSeconds(1))).isEqualTo(1);
    }
}
