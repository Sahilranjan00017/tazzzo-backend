package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.common.money.Currency;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.pricing.Price;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceReadPort;
import com.tazzzo.pricing.PriceStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-12C hardening UNIT: the shared "visible facts → base → fail-closed → current-price overlay"
 * seam used by BOTH the public list and the customer SKU batch reader. Pure, no Mongo.
 */
class CurrentCardBaseComposerTest {

    private static final CatalogCardFacts FACTS =
            new CatalogCardFacts("TZP-7", "PRD-7", "Fresh title", "BR", "TZV-1", 42L);

    private static ProductCardBaseProjection staleBase() {
        return new ProductCardBaseProjection("TZP-6", "TZP-6", "Stale", "BR", "TZV-1", PriceStatus.ACTIVE,
                9999L, 12000L, "INR", "k/x.webp", 5L, 1L, 2L, 7L);
    }

    private static PriceLookup active(String sku, long selling, long mrp) {
        return PriceLookup.of(PriceStatus.ACTIVE, new Price(sku, Currency.INR, selling, mrp, 9L, true,
                Instant.parse("2026-01-01T00:00:00Z"), null));
    }

    private static ProductCardBaseReadPort basesOf(ProductCardBaseProjection... rows) {
        return new ProductCardBaseReadPort() {
            @Override public Optional<ProductCardBaseProjection> findBySku(String skuId) {
                return Arrays.stream(rows).filter(r -> r.skuId().equals(skuId)).findFirst();
            }
            @Override public List<ProductCardBaseProjection> findBySkuIds(Collection<String> skuIds) {
                return Arrays.stream(rows).filter(r -> skuIds.contains(r.skuId())).toList();
            }
        };
    }

    private static PriceReadPort pricesOf(Map<String, PriceLookup> map) {
        return new PriceReadPort() {
            @Override public PriceLookup findCurrentPrice(String skuId) {
                return map.getOrDefault(skuId, PriceLookup.missing());
            }
            @Override public Map<String, PriceLookup> findCurrentPrices(Collection<String> skuIds, Currency c) {
                Map<String, PriceLookup> out = new java.util.LinkedHashMap<>();
                skuIds.forEach(s -> out.put(s, findCurrentPrice(s)));
                return out;
            }
        };
    }

    @Test void a_gap_sku_is_a_fail_closed_base_with_fresh_catalog_identity_and_never_a_price() {
        CurrentCardBaseComposer c = new CurrentCardBaseComposer(basesOf(), pricesOf(Map.of()));
        List<String> gaps = new ArrayList<>();
        ProductCardBaseProjection b = c.compose(List.of("TZP-7"), s -> FACTS, gaps::add).get(0);

        assertEquals(List.of("TZP-7"), gaps, "freshness gap signalled exactly once");
        assertEquals("TZP-7", b.skuId());
        assertEquals("PRD-7", b.productId(), "productId comes from fresh facts, never assumed == skuId");
        assertEquals("Fresh title", b.title());
        assertEquals(42L, b.catalogVersion(), "catalogVersion comes from fresh facts, never 0");
        assertEquals(PriceStatus.MISSING, b.priceStatus());
        assertNull(b.sellingPricePaise());
        assertNull(b.primaryAssetKey(), "no media fabricated");
    }

    @Test void a_gap_sku_still_receives_the_current_canonical_price_through_the_overlay() {
        CurrentCardBaseComposer c = new CurrentCardBaseComposer(basesOf(),
                pricesOf(Map.of("TZP-7", active("TZP-7", 26500L, 30000L))));
        ProductCardBaseProjection b = c.compose(List.of("TZP-7"), s -> FACTS, s -> { }).get(0);
        assertEquals(26500L, b.sellingPricePaise());
        assertEquals(PriceStatus.ACTIVE, b.priceStatus());
        assertEquals(42L, b.catalogVersion());
    }

    @Test void a_present_base_is_overlaid_with_current_price_and_facts_are_never_consulted() {
        CurrentCardBaseComposer c = new CurrentCardBaseComposer(basesOf(staleBase()),
                pricesOf(Map.of("TZP-6", active("TZP-6", 26500L, 30000L))));
        ProductCardBaseProjection b = c.compose(List.of("TZP-6"),
                s -> { throw new AssertionError("facts asked for a SKU that has a base row"); },
                s -> fail("no gap")).get(0);
        assertEquals(26500L, b.sellingPricePaise(), "stale projected price replaced");
        assertEquals("Stale", b.title(), "identity stays from the base row");
        assertEquals("k/x.webp", b.primaryAssetKey());
    }

    @Test void missing_current_price_fails_closed_even_when_the_base_row_carries_a_price() {
        CurrentCardBaseComposer c = new CurrentCardBaseComposer(basesOf(staleBase()), pricesOf(Map.of()));
        ProductCardBaseProjection b = c.compose(List.of("TZP-6"), s -> FACTS, s -> { }).get(0);
        assertNull(b.sellingPricePaise());
        assertEquals(PriceStatus.MISSING, b.priceStatus());
    }

    @Test void input_order_is_preserved_and_batches_are_used_not_per_sku_reads() {
        AtomicInteger baseCalls = new AtomicInteger();
        AtomicInteger priceCalls = new AtomicInteger();
        ProductCardBaseReadPort bases = new ProductCardBaseReadPort() {
            @Override public Optional<ProductCardBaseProjection> findBySku(String s) { throw new AssertionError(); }
            @Override public List<ProductCardBaseProjection> findBySkuIds(Collection<String> s) {
                baseCalls.incrementAndGet();
                return List.of();
            }
        };
        PriceReadPort prices = new PriceReadPort() {
            @Override public PriceLookup findCurrentPrice(String s) { throw new AssertionError("per-SKU read"); }
            @Override public Map<String, PriceLookup> findCurrentPrices(Collection<String> s, Currency c) {
                priceCalls.incrementAndGet();
                return Map.of();
            }
        };
        List<String> order = List.of("TZP-3", "TZP-1", "TZP-2");
        List<ProductCardBaseProjection> out = new CurrentCardBaseComposer(bases, prices).compose(order,
                s -> new CatalogCardFacts(s, s, "T " + s, null, null, 1L), s -> { });
        assertEquals(order, out.stream().map(ProductCardBaseProjection::skuId).toList());
        assertEquals(1, baseCalls.get());
        assertEquals(1, priceCalls.get());
    }

    @Test void over_bound_facts_fail_closed_typed_not_as_a_raw_illegal_argument() {
        CatalogCardFacts tooLong = new CatalogCardFacts("TZP-9", "TZP-9", "x".repeat(501), null, null, 1L);
        CurrentCardBaseComposer c = new CurrentCardBaseComposer(basesOf(), pricesOf(Map.of()));
        assertThrows(CurrentCardBaseComposer.FactsOutOfBoundsException.class,
                () -> c.compose(List.of("TZP-9"), s -> tooLong, s -> { }));
    }

    @Test void the_batch_reader_produces_the_composers_fail_closed_identity_end_to_end() {
        CommerceSkuBatchReader reader = new CommerceSkuBatchReader(sku -> Optional.of(FACTS), basesOf(),
                pricesOf(Map.of()), new ProductCardRuntimeEnricher(p -> { throw new AssertionError(); },
                (s, l) -> { throw new AssertionError(); }, MediaUrlResolver.of("https://media.test.example")));
        RuntimeProductCard card = reader.readCurrent(List.of("TZP-7"), LocationQuery.anonymous()).get("TZP-7");
        assertEquals("PRD-7", card.productId());
        assertEquals("Fresh title", card.title());
        assertNull(card.sellingPricePaise());
        assertFalse(card.buyable(), "a freshness gap never sells");
    }

    /** Structural guard: the two consumers share ONE seam and keep no private fail-closed copy. */
    @Test void list_service_and_batch_reader_both_use_the_shared_composer_and_have_no_private_copy() {
        for (Class<?> consumer : List.of(CommerceListService.class, CommerceSkuBatchReader.class)) {
            boolean holds = Arrays.stream(consumer.getDeclaredFields())
                    .map(Field::getType).anyMatch(CurrentCardBaseComposer.class::equals);
            assertTrue(holds, consumer.getSimpleName() + " must hold the shared CurrentCardBaseComposer");
            assertTrue(Arrays.stream(consumer.getDeclaredMethods()).map(Method::getName)
                            .noneMatch("failClosedBase"::equals),
                    consumer.getSimpleName() + " must not carry its own failClosedBase");
        }
    }
}
