package com.tazzzo.pricing;

import com.tazzzo.common.money.Currency;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 16 UNIT: command validation, no Mongo. */
class PricingValidationTest {

    private UpsertPriceCommand cmd(long selling, long mrp, Currency cur, Instant from, Instant to, Long ver) {
        return new UpsertPriceCommand("TZP-1", selling, mrp, cur, from, to, "seed", ver);
    }

    @Test void zero_price_is_valid() {
        assertDoesNotThrow(() -> PricingService.validateCommand(cmd(0, 0, Currency.INR, null, null, null)));
    }

    @Test void positive_price_valid() {
        Price p = PricingService.validateCommand(cmd(26500, 30000, Currency.INR, null, null, null));
        assertEquals(26500, p.sellingPricePaise());
        assertEquals(30000, p.mrpPaise());
        assertEquals(1, p.version());
    }

    @Test void selling_above_mrp_rejected() {
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(cmd(30001, 30000, Currency.INR, null, null, null)));
    }

    @Test void negative_selling_rejected() {
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(cmd(-1, 100, Currency.INR, null, null, null)));
    }

    @Test void negative_mrp_rejected() {
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(cmd(0, -1, Currency.INR, null, null, null)));
    }

    @Test void null_currency_rejected() {
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(cmd(100, 200, null, null, null, null)));
    }

    @Test void invalid_effective_window_rejected() {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(cmd(100, 200, Currency.INR, t, t, null))); // to == from
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(cmd(100, 200, Currency.INR, t.plusSeconds(10), t, null))); // to < from
    }

    @Test void expected_version_must_be_positive() {
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(cmd(100, 200, Currency.INR, null, null, 0L)));
    }

    @Test void blank_sku_rejected() {
        assertThrows(InvalidPriceException.class,
                () -> PricingService.validateCommand(new UpsertPriceCommand(" ", 1, 2, Currency.INR, null, null, "s", null)));
    }

    @Test void update_intent_bumps_version() {
        Price p = PricingService.validateCommand(cmd(100, 200, Currency.INR, null, null, 4L));
        assertEquals(5, p.version());
    }
}
