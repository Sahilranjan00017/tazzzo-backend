package com.tazzzo.pricing;

import com.tazzzo.common.money.Currency;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-10A UNIT: the {@link PriceReadPort} default batch honours its contract — duplicates
 * deduplicated (one point read each), every requested SKU present, null collection rejected.
 */
class PriceReadPortDefaultTest {

    private static final class CountingPort implements PriceReadPort {
        final List<String> reads = new ArrayList<>();
        @Override public PriceLookup findCurrentPrice(String skuId) {
            reads.add(skuId);
            return PriceLookup.missing();
        }
    }

    @Test void duplicates_cost_one_read_each_first_seen_order() {
        CountingPort port = new CountingPort();
        Map<String, PriceLookup> out =
                port.findCurrentPrices(List.of("SKU-A", "SKU-A", "SKU-B", "SKU-A"), Currency.INR);
        assertEquals(List.of("SKU-A", "SKU-B"), port.reads, "each distinct SKU read exactly once");
        assertEquals(2, out.size());
        assertEquals(PriceStatus.MISSING, out.get("SKU-A").status());
        assertEquals(PriceStatus.MISSING, out.get("SKU-B").status());
    }

    @Test void null_collection_and_null_currency_rejected() {
        CountingPort port = new CountingPort();
        assertThrows(NullPointerException.class, () -> port.findCurrentPrices(null, Currency.INR));
        assertThrows(NullPointerException.class, () -> port.findCurrentPrices(List.of("SKU-A"), null));
    }

    @Test void empty_input_is_empty_result_zero_reads() {
        CountingPort port = new CountingPort();
        assertTrue(port.findCurrentPrices(List.of(), Currency.INR).isEmpty());
        assertTrue(port.reads.isEmpty());
    }
}
