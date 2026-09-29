package com.tazzzo.customer.checkout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PR-13A hardening — {@link CheckoutQuote} is meant to be a TRUSTED snapshot for a future Order: its
 * compact constructor must fail loud (never normalize) on any invariant violation. Pure, no Mongo.
 */
class CheckoutQuoteInvariantTest {

    private static final String QUOTE_ID = "CHKQ_" + "a".repeat(20);
    private static final String ADDR = "ADDR_" + "b".repeat(10);
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(300);

    private static CheckoutQuote.Line line(String sku, int qty, long unit, long total) {
        return new CheckoutQuote.Line(sku, qty, unit, total);
    }

    private static CheckoutQuote valid() {
        return new CheckoutQuote(QUOTE_ID, 1, ADDR, List.of(line("TZP-1", 2, 1000, 2000)), 2, 2000, "INR",
                CREATED, EXPIRES);
    }

    @Test void a_well_formed_quote_constructs() {
        assertValidConstructs(valid());
    }

    private static void assertValidConstructs(CheckoutQuote q) {
        org.junit.jupiter.api.Assertions.assertNotNull(q);
    }

    @Test void invalid_quote_id_shape_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote("not-a-quote-id", 1, ADDR,
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "INR", CREATED, EXPIRES));
    }

    @Test void negative_cart_version_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, -1, ADDR,
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "INR", CREATED, EXPIRES));
    }

    @Test void invalid_address_id_shape_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, "not-an-address",
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "INR", CREATED, EXPIRES));
    }

    @Test void empty_lines_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR, List.of(), 0, 0,
                "INR", CREATED, EXPIRES));
    }

    @Test void a_line_with_quantity_below_one_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> line("TZP-1", 0, 100, 0));
    }

    @Test void a_line_with_a_negative_unit_price_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> line("TZP-1", 1, -1, -1));
    }

    @Test void a_line_whose_total_does_not_equal_unit_times_quantity_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> line("TZP-1", 2, 100, 199));
    }

    @Test void duplicate_sku_across_lines_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 1, 100, 100), line("TZP-1", 1, 200, 200)), 2, 300, "INR", CREATED, EXPIRES));
    }

    @Test void item_count_not_matching_the_sum_of_quantities_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 2, 100, 200)), 1, 200, "INR", CREATED, EXPIRES));
    }

    @Test void subtotal_not_matching_the_sum_of_line_totals_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 2, 100, 200)), 2, 199, "INR", CREATED, EXPIRES));
    }

    @Test void non_inr_currency_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "USD", CREATED, EXPIRES));
    }

    @Test void created_at_not_before_expires_at_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "INR", EXPIRES, EXPIRES));
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "INR", EXPIRES.plusSeconds(1), EXPIRES));
    }

    @Test void null_created_or_expires_at_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "INR", null, EXPIRES));
        assertThrows(IllegalArgumentException.class, () -> new CheckoutQuote(QUOTE_ID, 1, ADDR,
                List.of(line("TZP-1", 1, 100, 100)), 1, 100, "INR", CREATED, null));
    }

    @Test void isExpired_uses_expiresAt_boundary_inclusive() {
        CheckoutQuote q = valid();
        org.junit.jupiter.api.Assertions.assertFalse(q.isExpired(EXPIRES.minusSeconds(1)));
        org.junit.jupiter.api.Assertions.assertTrue(q.isExpired(EXPIRES));
        org.junit.jupiter.api.Assertions.assertTrue(q.isExpired(EXPIRES.plusSeconds(1)));
    }
}
