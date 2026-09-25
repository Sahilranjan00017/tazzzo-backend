package com.tazzzo.pricing;

import com.tazzzo.common.money.Currency;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 12/16: effective-window boundary semantics (from inclusive, to exclusive, active gate). */
class PriceEffectiveTimeTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

    private Price price(boolean active, Instant from, Instant to) {
        return new Price("TZP-1", Currency.INR, 100, 200, 1, active, from, to);
    }

    @Test void open_ended_active_now() {
        assertEquals(PriceStatus.ACTIVE, price(true, null, null).statusAt(Instant.now()));
        assertTrue(price(true, null, null).isUsableAt(Instant.now()));
    }

    @Test void not_yet_effective_before_from() {
        assertEquals(PriceStatus.NOT_YET_EFFECTIVE,
                price(true, FROM, TO).statusAt(FROM.minusSeconds(1)));
    }

    @Test void exactly_at_from_is_active_inclusive() {
        assertEquals(PriceStatus.ACTIVE, price(true, FROM, TO).statusAt(FROM));
    }

    @Test void inside_window_active() {
        assertEquals(PriceStatus.ACTIVE, price(true, FROM, TO).statusAt(FROM.plusSeconds(3600)));
    }

    @Test void exactly_at_to_is_expired_exclusive() {
        assertEquals(PriceStatus.EXPIRED, price(true, FROM, TO).statusAt(TO));
    }

    @Test void after_to_expired() {
        assertEquals(PriceStatus.EXPIRED, price(true, FROM, TO).statusAt(TO.plusSeconds(1)));
    }

    @Test void inactive_overrides_window() {
        assertEquals(PriceStatus.INACTIVE, price(false, FROM, TO).statusAt(FROM.plusSeconds(10)));
    }
}
