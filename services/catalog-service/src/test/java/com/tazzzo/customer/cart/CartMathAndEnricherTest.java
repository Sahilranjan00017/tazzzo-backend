package com.tazzzo.customer.cart;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.read.CommerceReadUnavailableException;
import com.tazzzo.commerce.read.CommerceSkuBatchReader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR-12C — int64 paise arithmetic and the enricher's degraded/overflow behaviour (no I/O). */
class CartMathAndEnricherTest {

    @Test void line_total_is_exact_int64_math() {
        assertThat(CartMath.lineTotal(3_000_000_000_000L, 20)).isEqualTo(60_000_000_000_000L);
        assertThat(CartMath.lineTotal(Long.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
    }

    @Test void line_total_overflow_is_detected_not_wrapped() {
        assertThatThrownBy(() -> CartMath.lineTotal(Long.MAX_VALUE / 2 + 1, 2)).isInstanceOf(ArithmeticException.class);
    }

    @Test void subtotal_addition_overflow_is_detected_not_wrapped() {
        assertThat(CartMath.add(1L, 2L)).isEqualTo(3L);
        assertThatThrownBy(() -> CartMath.add(Long.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
    }

    private static CartState state(String... skus) {
        Instant t = Instant.parse("2026-06-01T00:00:00Z");
        return new CartState(3, java.util.Arrays.stream(skus).map(s -> new CartState.Line(s, 2, t, t)).toList(),
                t.plusSeconds(3600), false);
    }

    @Test void commerce_outage_degrades_to_unknown_with_a_bounded_issue_never_false_certainty() {
        CommerceSkuBatchReader reader = Mockito.mock(CommerceSkuBatchReader.class);
        Mockito.when(reader.readCurrent(Mockito.any(), Mockito.any()))
                .thenThrow(new CommerceReadUnavailableException(CommerceReadUnavailableException.Category.PRICING,
                        "boom"));
        CartResponseDto dto = new CartEnricher(reader).present(state("TZP-1"), LocationQuery.anonymous(), "req_1");
        CartResponseDto.Item i = dto.items().get(0);
        assertThat(i.issues()).containsExactly(CartIssue.ENRICHMENT_UNAVAILABLE);
        assertThat(i.buyable()).isFalse();
        assertThat(i.availability().stockState()).isEqualTo("UNKNOWN");
        assertThat(i.availability().serviceable()).isNull();
        assertThat(i.price()).isNull();
        assertThat(i.lineTotalPaise()).isNull();
        assertThat(dto.subtotalPaise()).isZero();
        assertThat(dto.itemCount()).isEqualTo(2);
    }

    @Test void a_sku_missing_from_the_commerce_read_is_product_unavailable() {
        CommerceSkuBatchReader reader = Mockito.mock(CommerceSkuBatchReader.class);
        Mockito.when(reader.readCurrent(Mockito.any(), Mockito.any())).thenReturn(java.util.Map.of());
        CartResponseDto dto = new CartEnricher(reader).present(state("TZP-1"), LocationQuery.anonymous(), "req_1");
        assertThat(dto.items().get(0).issues()).contains(CartIssue.PRODUCT_UNAVAILABLE);
        assertThat(dto.items().get(0).buyable()).isFalse();
    }

    @Test void an_empty_cart_never_calls_commerce() {
        CommerceSkuBatchReader reader = Mockito.mock(CommerceSkuBatchReader.class);
        CartResponseDto dto = new CartEnricher(reader).present(CartState.empty(0), LocationQuery.anonymous(), "r");
        assertThat(dto.items()).isEmpty();
        Mockito.verifyNoInteractions(reader);
    }

    @Test void limit_properties_reject_out_of_range_values() {
        CartLimitProperties p = new CartLimitProperties();
        assertThat(p.getMaxDistinctItems()).isEqualTo(50);
        assertThat(p.getMaxQuantityPerItem()).isEqualTo(20);
        p.setMaxDistinctItems(0);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
        p.setMaxDistinctItems(51);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
        p.setMaxDistinctItems(50);
        p.setMaxQuantityPerItem(0);
        assertThatThrownBy(p::validate).isInstanceOf(IllegalStateException.class);
    }

    // ---------- cart age policy: PRICE_CHANGED only in the REVALIDATE band ----------

    private static com.tazzzo.commerce.read.RuntimeProductCard priced(String sku, long sellingPaise) {
        return new com.tazzzo.commerce.read.RuntimeProductCard(sku, sku, "T " + sku, null, null, null,
                sellingPaise, null, null, null, com.tazzzo.commerce.contract.StockState.IN_STOCK, null, 5, 1,
                Boolean.TRUE, null, null, true);
    }

    private static CartState aged(CartState.Freshness freshness, Long observedPaise) {
        Instant t = Instant.parse("2026-06-01T00:00:00Z");
        return new CartState(4, List.of(new CartState.Line("TZP-1", 2, t, t, observedPaise)), t.plusSeconds(3600), false,
                freshness);
    }

    private static CartResponseDto present(CartState state, long currentPaise) {
        CommerceSkuBatchReader reader = Mockito.mock(CommerceSkuBatchReader.class);
        Mockito.when(reader.readCurrent(Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Map.of("TZP-1", priced("TZP-1", currentPaise)));
        return new CartEnricher(reader).present(state,
                LocationQuery.ofPin(new com.tazzzo.commerce.contract.Pincode("560001")), "req_1");
    }

    @Test void a_revalidate_cart_flags_a_moved_price_but_stays_buyable_at_the_current_price() {
        CartResponseDto dto = present(aged(CartState.Freshness.REVALIDATE, 100L), 120L);
        CartResponseDto.Item i = dto.items().get(0);
        assertThat(dto.freshness()).isEqualTo("REVALIDATE");
        assertThat(i.issues()).containsExactly(CartIssue.PRICE_CHANGED);
        assertThat(i.buyable()).as("informational: the line is priced at the current price").isTrue();
        assertThat(i.price().unitPricePaise()).isEqualTo(120L);
        assertThat(i.lineTotalPaise()).isEqualTo(240L);
        assertThat(dto.subtotalPaise()).isEqualTo(240L);
    }

    @Test void price_changed_is_never_raised_for_a_fresh_cart_an_unchanged_price_or_an_unknown_observation() {
        assertThat(present(aged(CartState.Freshness.FRESH, 100L), 120L).items().get(0).issues()).isEmpty();
        assertThat(present(aged(CartState.Freshness.FRESH, 100L), 120L).freshness()).isEqualTo("FRESH");
        assertThat(present(aged(CartState.Freshness.REVALIDATE, 120L), 120L).items().get(0).issues()).isEmpty();
        assertThat(present(aged(CartState.Freshness.REVALIDATE, null), 120L).items().get(0).issues()).isEmpty();
    }
}
