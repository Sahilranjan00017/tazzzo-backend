package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.contract.StockState;
import com.tazzzo.inventory.InventoryLookup;
import com.tazzzo.inventory.InventoryReadPort;
import com.tazzzo.inventory.InventoryRecord;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.pricing.PriceStatus;
import com.tazzzo.serviceability.ServiceabilityReadPort;
import com.tazzzo.serviceability.ServiceabilityResolution;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** PR-08 UNIT: the full STEP 33 composition matrix — pure, stub-driven, no Mongo. */
class RuntimeEnricherTest {

    private static final Pincode PIN = new Pincode("560047");
    private static final LocationQuery AT_PIN = LocationQuery.ofPin(PIN);

    private final ServiceabilityReadPort neverServiceability =
            pin -> { throw new AssertionError("serviceability must not be called"); };
    private final InventoryReadPort neverInventory =
            (sku, loc) -> { throw new AssertionError("inventory must not be called"); };
    private final ServiceabilityReadPort serviceableAt =
            pin -> ServiceabilityResolution.serviceable("SA-1", "FL-1");

    private ProductCardBaseProjection base(String sku, Long selling, Long mrp,
                                           PriceStatus status, String assetKey) {
        return new ProductCardBaseProjection(sku, sku, "Title " + sku, "BR", "TZV-1",
                status, selling, mrp, selling == null ? null : "INR", assetKey, 1L, 1L, 1L, 1L);
    }

    private ProductCardBaseProjection pricedBase(String sku) {
        return base(sku, 26500L, 30000L, PriceStatus.ACTIVE, "p/x/f.webp");
    }

    private InventoryReadPort stock(long onHand, long reserved, long threshold, long cap, boolean active) {
        return (sku, loc) -> {
            InventoryRecord r = new InventoryRecord(sku, loc, onHand, reserved, threshold, cap, 1, active);
            return active ? InventoryLookup.of(InventoryLookup.Status.PRESENT, r)
                    : InventoryLookup.of(InventoryLookup.Status.INACTIVE, r);
        };
    }

    private ProductCardRuntimeEnricher enricher(ServiceabilityReadPort s, InventoryReadPort i) {
        return new ProductCardRuntimeEnricher(s, i, MediaUrlResolver.of("https://media.test.example"));
    }

    // --- location modes -----------------------------------------------------------

    @Test void anonymous_card_calls_no_location_ports() {
        RuntimeProductPage page = enricher(neverServiceability, neverInventory)
                .enrichPage(List.of(pricedBase("TZP-1")), LocationQuery.anonymous());
        RuntimeProductCard c = page.cards().get(0);
        assertEquals(StockState.UNKNOWN, c.stockState());
        assertNull(c.serviceable());
        assertNull(c.lowStockRemaining());
        assertEquals(0, c.maxOrderQuantity());
        assertEquals(1, c.minimumOrderQuantity());
        assertNull(c.etaMinutesMin());
        assertNull(c.etaMinutesMax());
        assertFalse(c.buyable(), "active price + no location => never buyable");
        assertNull(page.serviceArea());
        // identity/price/media still render:
        assertEquals(26500L, c.sellingPricePaise());
        assertEquals("https://media.test.example/p/x/f.webp", c.thumbnailUrl());
    }

    @Test void latlng_is_typed_unsupported_not_silent_anonymous() {
        assertThrows(UnsupportedLocationException.class, () ->
                enricher(neverServiceability, neverInventory)
                        .enrichPage(List.of(pricedBase("TZP-1")), LocationQuery.ofLatLng(12.9, 77.6)));
    }

    // --- serviceability statuses ----------------------------------------------------

    @Test void serviceable_in_stock_is_buyable() {
        RuntimeProductPage page = enricher(serviceableAt, stock(50, 0, 3, 10, true))
                .enrichPage(List.of(pricedBase("TZP-1")), AT_PIN);
        RuntimeProductCard c = page.cards().get(0);
        assertEquals(StockState.IN_STOCK, c.stockState());
        assertEquals(Boolean.TRUE, c.serviceable());
        assertEquals(10, c.maxOrderQuantity(), "min(cap=10, available=50)");
        assertTrue(c.buyable());
        assertEquals(new RuntimeServiceArea("SA-1", true), page.serviceArea());
    }

    @Test void serviceable_low_stock_exposes_remaining() {
        RuntimeProductCard c = enricher(serviceableAt, stock(3, 1, 3, 10, true))
                .enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertEquals(StockState.LOW_STOCK, c.stockState());
        assertEquals(2, c.lowStockRemaining(), "available=2, never onHand/reserved");
        assertEquals(2, c.maxOrderQuantity());
        assertTrue(c.buyable());
    }

    @Test void serviceable_out_of_stock_not_buyable() {
        RuntimeProductCard c = enricher(serviceableAt, stock(5, 5, 3, 10, true))
                .enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertEquals(StockState.OUT_OF_STOCK, c.stockState());
        assertNull(c.lowStockRemaining());
        assertEquals(0, c.maxOrderQuantity());
        assertFalse(c.buyable());
    }

    @Test void inventory_missing_is_unknown_never_out_of_stock() {
        InventoryReadPort missing = (sku, loc) -> InventoryLookup.missing();
        RuntimeProductCard c = enricher(serviceableAt, missing).enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertEquals(StockState.UNKNOWN, c.stockState());
        assertEquals(0, c.maxOrderQuantity());
        assertFalse(c.buyable());
        assertEquals(Boolean.TRUE, c.serviceable(), "area serviceable; stock unknown");
    }

    @Test void inventory_inactive_is_unknown_not_buyable() {
        RuntimeProductCard c = enricher(serviceableAt, stock(50, 0, 3, 10, false))
                .enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertEquals(StockState.UNKNOWN, c.stockState());
        assertFalse(c.buyable());
    }

    @Test void unserviceable_pin_never_queries_inventory() {
        ServiceabilityReadPort unserviceable = pin -> ServiceabilityResolution.unserviceable();
        RuntimeProductPage page = enricher(unserviceable, neverInventory)
                .enrichPage(List.of(pricedBase("TZP-1")), AT_PIN);
        RuntimeProductCard c = page.cards().get(0);
        assertEquals(StockState.UNKNOWN, c.stockState(), "no fake out-of-stock claim");
        assertEquals(Boolean.FALSE, c.serviceable());
        assertEquals(0, c.maxOrderQuantity());
        assertFalse(c.buyable());
        assertNull(page.serviceArea(), "no configured area -> summary omitted");
    }

    @Test void inactive_area_and_no_active_route_degrade_without_inventory() {
        for (ServiceabilityResolution r : List.of(
                ServiceabilityResolution.inactive("SA-9"),
                ServiceabilityResolution.noActiveRoute("SA-9"))) {
            RuntimeProductPage page = enricher(pin -> r, neverInventory)
                    .enrichPage(List.of(pricedBase("TZP-1")), AT_PIN);
            assertEquals(StockState.UNKNOWN, page.cards().get(0).stockState());
            assertFalse(page.cards().get(0).buyable());
            assertEquals(new RuntimeServiceArea("SA-9", false), page.serviceArea());
        }
    }

    // --- pricing / buyability -------------------------------------------------------

    @Test void price_missing_or_inactive_never_buyable_even_with_stock() {
        for (PriceStatus s : List.of(PriceStatus.MISSING, PriceStatus.INACTIVE,
                PriceStatus.EXPIRED, PriceStatus.NOT_YET_EFFECTIVE)) {
            RuntimeProductCard c = enricher(serviceableAt, stock(50, 0, 3, 10, true))
                    .enrichOne(base("TZP-1", null, null, s, null), AT_PIN);
            assertFalse(c.buyable(), "status " + s + " must not be buyable");
            assertNull(c.sellingPricePaise());
            assertEquals(StockState.IN_STOCK, c.stockState(), "stock still truthful");
        }
    }

    @Test void max_below_min_not_buyable() {
        // cap 0 => effective 0 < min 1
        RuntimeProductCard c = enricher(serviceableAt, stock(50, 0, 3, 0, true))
                .enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertEquals(0, c.maxOrderQuantity());
        assertFalse(c.buyable());
    }

    // --- discount semantics -----------------------------------------------------------

    @Test void discount_absent_when_mrp_equals_selling() {
        RuntimeProductCard c = enricher(serviceableAt, stock(5, 0, 1, 10, true))
                .enrichOne(base("TZP-1", 200L, 200L, PriceStatus.ACTIVE, null), AT_PIN);
        assertNull(c.discountAmountPaise());
        assertNull(c.discountPercent());
    }

    @Test void discount_floor_rounding_deterministic() {
        RuntimeProductCard c = enricher(serviceableAt, stock(5, 0, 1, 10, true))
                .enrichOne(pricedBase("TZP-1"), AT_PIN); // 26500 / 30000
        assertEquals(3500L, c.discountAmountPaise());
        assertEquals(11, c.discountPercent(), "floor(3500*100/30000)=11");
    }

    @Test void free_item_omits_percent_keeps_amount() {
        RuntimeProductCard c = enricher(serviceableAt, stock(5, 0, 1, 10, true))
                .enrichOne(base("TZP-1", 0L, 5000L, PriceStatus.ACTIVE, null), AT_PIN);
        assertEquals(5000L, c.discountAmountPaise());
        assertNull(c.discountPercent(), "100%-off must not be misrepresented as 99%");
    }

    // --- media ---------------------------------------------------------------------

    @Test void media_key_absent_yields_null_thumbnail() {
        RuntimeProductCard c = enricher(serviceableAt, stock(5, 0, 1, 10, true))
                .enrichOne(base("TZP-1", 100L, 200L, PriceStatus.ACTIVE, null), AT_PIN);
        assertNull(c.thumbnailUrl());
        assertTrue(c.buyable(), "missing image never corrupts stock/price behavior");
    }

    @Test void unconfigured_resolver_degrades_to_null_thumbnail() {
        ProductCardRuntimeEnricher e = new ProductCardRuntimeEnricher(
                serviceableAt, stock(5, 0, 1, 10, true), MediaUrlResolver.unconfigured());
        RuntimeProductCard c = e.enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertNull(c.thumbnailUrl());
        assertTrue(c.buyable(), "readiness gap degrades imagery only");
    }

    // --- quantity conversion safety -----------------------------------------------------

    @Test void pathological_quantity_fails_loudly_never_silent_cast() {
        InventoryReadPort huge = (sku, loc) -> InventoryLookup.of(InventoryLookup.Status.PRESENT,
                new InventoryRecord(sku, loc, Long.MAX_VALUE / 2, 0, 1, Long.MAX_VALUE / 2, 1, true));
        assertThrows(ArithmeticException.class, () ->
                enricher(serviceableAt, huge).enrichOne(pricedBase("TZP-1"), AT_PIN));
    }

    // --- infrastructure failure propagation -----------------------------------------------

    @Test void serviceability_infrastructure_failure_propagates_typed() {
        ServiceabilityReadPort broken = pin -> { throw new RuntimeException("serviceability outage"); };
        RuntimeException e = assertThrows(RuntimeException.class, () ->
                enricher(broken, neverInventory).enrichPage(List.of(pricedBase("TZP-1")), AT_PIN));
        assertTrue(e.getMessage().contains("outage"), "outage never disguised as business state");
    }

    @Test void inventory_infrastructure_failure_propagates_typed() {
        InventoryReadPort broken = (sku, loc) -> { throw new RuntimeException("inventory outage"); };
        assertThrows(RuntimeException.class, () ->
                enricher(serviceableAt, broken).enrichPage(List.of(pricedBase("TZP-1")), AT_PIN));
    }

    // --- output boundary ---------------------------------------------------------------

    @Test void BLOCKER_runtime_models_carry_no_internal_location_or_counter_fields() {
        List<String> forbidden = List.of("fulfillment", "onhand", "on_hand", "reserved",
                "inventoryversion", "assetkey", "warehouse", "storeid", "darkstore", "pincode");
        for (Class<?> c : List.of(RuntimeProductCard.class, RuntimeProductPage.class,
                RuntimeServiceArea.class)) {
            for (RecordComponent rc : c.getRecordComponents()) {
                String name = rc.getName().toLowerCase(Locale.ROOT);
                for (String bad : forbidden) {
                    assertFalse(name.contains(bad),
                            c.getSimpleName() + "." + rc.getName() + " leaks internal state");
                }
            }
        }
    }

    @Test void page_preserves_input_order() {
        RuntimeProductPage page = enricher(serviceableAt, stock(5, 0, 1, 10, true))
                .enrichPage(List.of(pricedBase("TZP-B"), pricedBase("TZP-A"), pricedBase("TZP-C")), AT_PIN);
        assertEquals(List.of("TZP-B", "TZP-A", "TZP-C"),
                page.cards().stream().map(RuntimeProductCard::skuId).toList());
    }

    // --- page bound (PR-08 review, STEP 6) ----------------------------------------------

    private List<ProductCardBaseProjection> pageOf(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> pricedBase("TZP-" + i)).toList();
    }

    @Test void page_of_exactly_max_size_accepted() {
        RuntimeProductPage page = enricher(serviceableAt, stock(5, 0, 1, 10, true))
                .enrichPage(pageOf(ProductCardRuntimeEnricher.MAX_PAGE_SIZE), AT_PIN);
        assertEquals(50, page.cards().size());
    }

    @Test void oversized_page_rejected_before_any_port_call() {
        // neverServiceability / neverInventory prove the guard fires BEFORE location dispatch.
        assertThrows(IllegalArgumentException.class, () ->
                enricher(neverServiceability, neverInventory).enrichPage(pageOf(51), AT_PIN));
    }

    @Test void oversized_page_rejected_even_anonymous() {
        assertThrows(IllegalArgumentException.class, () ->
                enricher(neverServiceability, neverInventory)
                        .enrichPage(pageOf(51), LocationQuery.anonymous()));
    }

    // --- empty-page semantics (PR-08 review, STEP 9 — decision A) ------------------------

    /** Batch-throwing stub: the lambda-based neverInventory cannot catch an EMPTY batch call
     *  (the default batch returns an empty map without a point read), so proving "zero
     *  inventory work" requires overriding the batch itself. */
    private InventoryReadPort neverEvenBatch() {
        return new InventoryReadPort() {
            @Override public InventoryLookup findInventory(String sku, String loc) {
                throw new AssertionError("inventory point read must not be called");
            }
            @Override public Map<String, InventoryLookup> findInventoryBatch(
                    Collection<String> skuIds, String loc) {
                throw new AssertionError("inventory batch must not be called");
            }
        };
    }

    @Test void empty_page_with_pin_resolves_serviceability_but_skips_inventory() {
        AtomicInteger svcCalls = new AtomicInteger();
        ServiceabilityReadPort counting = pin -> {
            svcCalls.incrementAndGet();
            return ServiceabilityResolution.serviceable("SA-1", "FL-1");
        };
        RuntimeProductPage page = enricher(counting, neverEvenBatch()).enrichPage(List.of(), AT_PIN);
        assertEquals(1, svcCalls.get(), "empty category still answers 'is my PIN served?'");
        assertTrue(page.cards().isEmpty());
        assertEquals(new RuntimeServiceArea("SA-1", true), page.serviceArea());
    }

    @Test void empty_page_anonymous_calls_no_ports_at_all() {
        RuntimeProductPage page = enricher(neverServiceability, neverEvenBatch())
                .enrichPage(List.of(), LocationQuery.anonymous());
        assertTrue(page.cards().isEmpty());
        assertNull(page.serviceArea());
    }

    // --- batch-response defence (PR-08 review, STEP 8) -----------------------------------

    private InventoryReadPort batchReturning(Map<String, InventoryLookup> response) {
        return new InventoryReadPort() {
            @Override public InventoryLookup findInventory(String sku, String loc) {
                throw new AssertionError("point read must not be used when batch is overridden");
            }
            @Override public Map<String, InventoryLookup> findInventoryBatch(
                    Collection<String> skuIds, String loc) {
                return response;
            }
        };
    }

    @Test void null_batch_map_fails_fast_as_adapter_bug() {
        assertThrows(NullPointerException.class, () ->
                enricher(serviceableAt, batchReturning(null))
                        .enrichPage(List.of(pricedBase("TZP-1")), AT_PIN));
    }

    @Test void key_mapped_to_null_fails_fast_never_becomes_business_missing() {
        Map<String, InventoryLookup> poisoned = new HashMap<>();
        poisoned.put("TZP-1", null); // adapter bug shape, distinct from an omitted key
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                enricher(serviceableAt, batchReturning(poisoned))
                        .enrichPage(List.of(pricedBase("TZP-1")), AT_PIN));
        assertTrue(e.getMessage().contains("TZP-1"));
    }

    @Test void omitted_key_degrades_as_missing_per_port_contract() {
        RuntimeProductCard c = enricher(serviceableAt, batchReturning(Map.of()))
                .enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertEquals(StockState.UNKNOWN, c.stockState());
        assertFalse(c.buyable());
    }

    @Test void extra_unrelated_batch_keys_are_ignored() {
        Map<String, InventoryLookup> response = new LinkedHashMap<>();
        response.put("TZP-1", InventoryLookup.of(InventoryLookup.Status.PRESENT,
                new InventoryRecord("TZP-1", "FL-1", 50, 0, 3, 10, 1, true)));
        response.put("SKU-GHOST", InventoryLookup.missing());
        RuntimeProductCard c = enricher(serviceableAt, batchReturning(response))
                .enrichOne(pricedBase("TZP-1"), AT_PIN);
        assertTrue(c.buyable(), "requested card composes normally; ghost key changes nothing");
    }

    // --- RuntimeProductCard low-stock invariants (PR-08 review, STEP 7) -------------------

    private RuntimeProductCard card(StockState state, Integer lowRemaining) {
        return new RuntimeProductCard("TZP-1", "TZP-1", "T", null, null, null,
                100L, null, null, null, state, lowRemaining, 5, 1, Boolean.TRUE, null, null, false);
    }

    @Test void low_stock_with_positive_remaining_accepted() {
        assertDoesNotThrow(() -> card(StockState.LOW_STOCK, 2));
    }

    @Test void low_stock_without_remaining_permitted() {
        // Deliberately one-directional: the invariant constrains what a remaining-count MEANS,
        // it does not force every LOW_STOCK producer to expose one.
        assertDoesNotThrow(() -> card(StockState.LOW_STOCK, null));
    }

    @Test void remaining_on_non_low_stock_states_rejected() {
        for (StockState s : List.of(StockState.IN_STOCK, StockState.OUT_OF_STOCK, StockState.UNKNOWN)) {
            assertThrows(IllegalArgumentException.class, () -> card(s, 2),
                    "lowStockRemaining must be unrepresentable on " + s);
        }
    }

    @Test void non_positive_remaining_rejected() {
        assertThrows(IllegalArgumentException.class, () -> card(StockState.LOW_STOCK, 0));
        assertThrows(IllegalArgumentException.class, () -> card(StockState.LOW_STOCK, -1));
    }
}
