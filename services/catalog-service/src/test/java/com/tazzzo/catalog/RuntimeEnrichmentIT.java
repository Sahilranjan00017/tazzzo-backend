package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.contract.StockState;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardBaseProjection;
import com.tazzzo.commerce.read.ProductCardBaseReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.commerce.read.ProductCardRuntimeEnricher;
import com.tazzzo.commerce.read.RuntimeProductCard;
import com.tazzzo.commerce.read.RuntimeProductPage;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.money.Currency;
import com.tazzzo.inventory.InventoryLookup;
import com.tazzzo.inventory.InventoryReadPort;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityReadPort;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-08 runtime enrichment — Testcontainers integration with REAL Serviceability, Inventory,
 * Pricing, Media and projection modules. Cross-location correctness is the primary boundary:
 * the BLOCKER tests prove per-PIN stock isolation and that shared service areas never imply
 * shared fulfillment.
 */
class RuntimeEnrichmentIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private PricingService pricing() {
        return new PricingService(new Tx(client), new WritePath(db), CLOCK);
    }

    private InventoryService inventoryService() {
        return new InventoryService(new Tx(client), new WritePath(db), CLOCK);
    }

    private ServiceabilityService serviceabilityService() {
        return new ServiceabilityService(new Tx(client), db, new DomainAudit(db, CLOCK), CLOCK);
    }

    private ProductCardProjectionService projector() {
        return new ProductCardProjectionService(new CatalogCardReader(db), pricing(),
                new com.tazzzo.media.MediaService(new Tx(client), new WritePath(db), CLOCK), db, CLOCK);
    }

    private ProductCardRuntimeEnricher enricher() {
        return new ProductCardRuntimeEnricher(serviceabilityService(), inventoryService(),
                MediaUrlResolver.of("https://media.test.example"));
    }

    /** Validator-conformant eligible product + canonical price + built base projection. */
    private ProductCardBaseProjection seedCard(String sku, long sellingPaise) {
        db.getCollection("products").insertOne(new Document("_id", sku)
                .append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", sku))
                .append("brand_code", "BR").append("title", "T " + sku)
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", "TZV-000037")
                        .append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", java.util.Date.from(NOW)));
        pricing().upsertPrice(new UpsertPriceCommand(sku, sellingPaise, sellingPaise + 5000,
                Currency.INR, null, null, "seed", null));
        projector().rebuildOne(sku);
        return new ProductCardBaseReader(db).findBySku(sku).orElseThrow();
    }

    private void route(String pin, String area, String location) {
        serviceabilityService().upsertServiceArea(new UpsertServiceAreaCommand(
                pin, area, List.of(new ServiceabilityRoute(location, 0, true)), "seed", null));
    }

    private void stockAt(String sku, String location, long onHand) {
        inventoryService().setInventory(new SetInventoryCommand(sku, location, onHand, 2, 10, "seed", null));
    }

    @Test void BLOCKER_cross_location_stock_never_leaks_between_pins() {
        ProductCardBaseProjection base = seedCard("TZP-XL1", 10000L);
        route("560001", "SA-A", "FL-A");
        route("560002", "SA-B", "FL-B");
        stockAt("TZP-XL1", "FL-A", 10);
        stockAt("TZP-XL1", "FL-B", 0);

        RuntimeProductCard a1 = enricher().enrichOne(base, LocationQuery.ofPin(new Pincode("560001")));
        assertEquals(StockState.IN_STOCK, a1.stockState());
        assertTrue(a1.buyable());

        RuntimeProductCard b = enricher().enrichOne(base, LocationQuery.ofPin(new Pincode("560002")));
        assertEquals(StockState.OUT_OF_STOCK, b.stockState());
        assertFalse(b.buyable());

        // A again: B's enrichment must not have contaminated anything.
        RuntimeProductCard a2 = enricher().enrichOne(base, LocationQuery.ofPin(new Pincode("560001")));
        assertEquals(StockState.IN_STOCK, a2.stockState());
        assertTrue(a2.buyable());
    }

    @Test void BLOCKER_shared_service_area_does_not_imply_shared_fulfillment() {
        // Two PINs, SAME public serviceAreaId, DIFFERENT internal fulfillment locations.
        ProductCardBaseProjection base = seedCard("TZP-SA1", 10000L);
        route("560003", "SA-SHARED", "FL-C");
        route("560004", "SA-SHARED", "FL-D");
        stockAt("TZP-SA1", "FL-C", 8);
        stockAt("TZP-SA1", "FL-D", 0);

        RuntimeProductPage pc = enricher().enrichPage(List.of(base), LocationQuery.ofPin(new Pincode("560003")));
        RuntimeProductPage pd = enricher().enrichPage(List.of(base), LocationQuery.ofPin(new Pincode("560004")));

        assertEquals("SA-SHARED", pc.serviceArea().serviceAreaId());
        assertEquals("SA-SHARED", pd.serviceArea().serviceAreaId());
        assertEquals(StockState.IN_STOCK, pc.cards().get(0).stockState());
        assertEquals(StockState.OUT_OF_STOCK, pd.cards().get(0).stockState(),
                "same area label must never be assumed to mean same stock — cache design input");
    }

    @Test void anonymous_page_makes_zero_serviceability_and_inventory_calls() {
        ProductCardBaseProjection base = seedCard("TZP-AN1", 10000L);
        AtomicInteger svcCalls = new AtomicInteger();
        AtomicInteger invCalls = new AtomicInteger();
        ServiceabilityReadPort countingSvc = pin -> {
            svcCalls.incrementAndGet();
            return serviceabilityService().resolveByPincode(pin);
        };
        InventoryReadPort countingInv = new InventoryReadPort() {
            @Override public InventoryLookup findInventory(String sku, String loc) {
                invCalls.incrementAndGet();
                return inventoryService().findInventory(sku, loc);
            }
            @Override public Map<String, InventoryLookup> findInventoryBatch(Collection<String> ids, String loc) {
                invCalls.incrementAndGet();
                return inventoryService().findInventoryBatch(ids, loc);
            }
        };
        new ProductCardRuntimeEnricher(countingSvc, countingInv, MediaUrlResolver.unconfigured())
                .enrichPage(List.of(base, base, base), LocationQuery.anonymous());
        assertEquals(0, svcCalls.get());
        assertEquals(0, invCalls.get());
    }

    @Test void serviceability_resolved_once_and_inventory_batched_once_for_a_page() {
        route("560005", "SA-P", "FL-P");
        List<ProductCardBaseProjection> bases = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String sku = "TZP-PG" + i;
            bases.add(seedCard(sku, 10000L + i));
            stockAt(sku, "FL-P", 5 + i);
        }
        AtomicInteger svcCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        AtomicInteger pointCalls = new AtomicInteger();
        ServiceabilityReadPort countingSvc = pin -> {
            svcCalls.incrementAndGet();
            return serviceabilityService().resolveByPincode(pin);
        };
        InventoryReadPort countingInv = new InventoryReadPort() {
            @Override public InventoryLookup findInventory(String sku, String loc) {
                pointCalls.incrementAndGet();
                return inventoryService().findInventory(sku, loc);
            }
            @Override public Map<String, InventoryLookup> findInventoryBatch(Collection<String> ids, String loc) {
                batchCalls.incrementAndGet();
                return inventoryService().findInventoryBatch(ids, loc);
            }
        };
        RuntimeProductPage page = new ProductCardRuntimeEnricher(countingSvc, countingInv,
                MediaUrlResolver.of("https://media.test.example"))
                .enrichPage(bases, LocationQuery.ofPin(new Pincode("560005")));

        assertEquals(1, svcCalls.get(), "ONE serviceability resolution per request (STEP 7)");
        assertEquals(1, batchCalls.get(), "ONE batched inventory read per page (STEP 19)");
        assertEquals(0, pointCalls.get(), "no N+1 point reads");
        assertEquals(20, page.cards().size());
        assertTrue(page.cards().stream().allMatch(RuntimeProductCard::buyable));
    }

    @Test void one_missing_inventory_row_degrades_only_that_sku() {
        route("560006", "SA-M", "FL-M");
        ProductCardBaseProjection withStock = seedCard("TZP-M1", 10000L);
        ProductCardBaseProjection noRow = seedCard("TZP-M2", 10000L);
        ProductCardBaseProjection oos = seedCard("TZP-M3", 10000L);
        stockAt("TZP-M1", "FL-M", 9);
        stockAt("TZP-M3", "FL-M", 0);

        RuntimeProductPage page = enricher().enrichPage(List.of(withStock, noRow, oos),
                LocationQuery.ofPin(new Pincode("560006")));
        assertEquals(StockState.IN_STOCK, page.cards().get(0).stockState());
        assertEquals(StockState.UNKNOWN, page.cards().get(1).stockState(), "missing != OUT_OF_STOCK");
        assertEquals(StockState.OUT_OF_STOCK, page.cards().get(2).stockState());
        assertTrue(page.cards().get(0).buyable(), "one bad SKU never poisons another");
        assertFalse(page.cards().get(1).buyable());
        assertFalse(page.cards().get(2).buyable());
    }

    @Test void enrichment_writes_nothing_and_base_stays_location_free() {
        ProductCardBaseProjection base = seedCard("TZP-RO1", 10000L);
        route("560007", "SA-RO", "FL-RO");
        stockAt("TZP-RO1", "FL-RO", 4);
        Document before = db.getCollection("product_card_base").find(Filters.eq("sku_id", "TZP-RO1")).first();

        enricher().enrichOne(base, LocationQuery.ofPin(new Pincode("560007")));
        enricher().enrichOne(base, LocationQuery.anonymous());

        Document after = db.getCollection("product_card_base").find(Filters.eq("sku_id", "TZP-RO1")).first();
        assertEquals(before, after, "runtime enrichment is ONE-WAY: zero writes to the base row");
        for (String key : after.keySet()) {
            String k = key.toLowerCase(java.util.Locale.ROOT);
            for (String bad : List.of("stock", "serviceable", "fulfillment", "buyable", "eta", "pincode")) {
                assertFalse(k.contains(bad), "base row gained location state: " + key);
            }
        }
    }

    @Test void concurrent_requests_with_different_pins_see_their_own_context() throws Exception {
        ProductCardBaseProjection base = seedCard("TZP-CC1", 10000L);
        route("560008", "SA-CA", "FL-CA");
        route("560009", "SA-CB", "FL-CB");
        stockAt("TZP-CC1", "FL-CA", 10);
        stockAt("TZP-CC1", "FL-CB", 0);

        ProductCardRuntimeEnricher shared = enricher(); // one instance, many threads (stateless)
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                final boolean pinA = i % 2 == 0;
                results.add(pool.submit(() -> {
                    start.await();
                    RuntimeProductCard c = shared.enrichOne(base, LocationQuery.ofPin(
                            new Pincode(pinA ? "560008" : "560009")));
                    return pinA
                            ? c.stockState() == StockState.IN_STOCK && c.buyable()
                            : c.stockState() == StockState.OUT_OF_STOCK && !c.buyable();
                }));
            }
            start.countDown();
            for (Future<Boolean> f : results) {
                assertTrue(f.get(), "every request must reflect its own location context");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void base_read_port_returns_rows_in_requested_order_skipping_missing() {
        seedCard("TZP-BR2", 10000L);
        seedCard("TZP-BR1", 10000L);
        List<ProductCardBaseProjection> rows = new ProductCardBaseReader(db)
                .findBySkuIds(List.of("TZP-BR2", "TZP-GHOST", "TZP-BR1"));
        assertEquals(List.of("TZP-BR2", "TZP-BR1"),
                rows.stream().map(ProductCardBaseProjection::skuId).toList());
    }
}
