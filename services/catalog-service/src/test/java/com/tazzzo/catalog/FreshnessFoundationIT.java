package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.ProjectionRebuildQueue;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.ProductUpdateService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.commerce.read.ProjectionReconciler;
import com.tazzzo.commerce.read.ProjectionRebuildWorker;
import com.tazzzo.commerce.read.RebuildOutcome;
import com.tazzzo.common.money.Currency;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaService;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.UpsertMediaSetCommand;
import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceStatus;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-10A production projection freshness foundation — Testcontainers integration with REAL
 * Pricing, Media, Catalog write paths, the work_queue, the rebuild worker and the reconciler.
 */
class FreshnessFoundationIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    // AbstractMongoIT drops only @BeforeAll (per class); these tests assert absolute work_queue /
    // projection counts, so clear the touched collections before each test for isolation.
    @BeforeEach
    void clean() {
        for (String c : List.of("work_queue", "product_card_base", "products",
                "price_current", "price_events", "media_refs")) {
            db.getCollection(c).deleteMany(new Document());
        }
    }

    private ProjectionRebuildQueue queue() {
        return new ProjectionRebuildQueue(db, CLOCK);
    }

    private PricingService pricingWithQueue() {
        return new PricingService(new Tx(client), new WritePath(db), CLOCK, queue());
    }

    private MediaService mediaWithQueue() {
        return new MediaService(new Tx(client), new WritePath(db), CLOCK, queue());
    }

    private ProductUpdateService catalogWithQueue() {
        WritePath wp = new WritePath(db);
        wp.setRebuildQueue(queue());
        return new ProductUpdateService(new Tx(client), wp);
    }

    private ProductCardProjectionService projector() {
        return new ProductCardProjectionService(new CatalogCardReader(db),
                new PricingService(new Tx(client), new WritePath(db), CLOCK),
                new MediaService(new Tx(client), new WritePath(db), CLOCK), db, CLOCK);
    }

    private ProjectionRebuildWorker worker() {
        return new ProjectionRebuildWorker(db, projector(), CLOCK);
    }

    private ProjectionReconciler reconciler() {
        return new ProjectionReconciler(db, queue());
    }

    private void seedProduct(String sku, String lifecycle) {
        db.getCollection("products").insertOne(new Document("_id", sku)
                .append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", sku))
                .append("brand_code", "BR").append("title", "T " + sku)
                .append("lifecycle", lifecycle)
                .append("classification", new Document("vertical_id", "TZV-000037")
                        .append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", java.util.Date.from(NOW)));
    }

    private void seedPrice(String sku, long selling, long mrp) {
        new PricingService(new Tx(client), new WritePath(db), CLOCK)
                .upsertPrice(new UpsertPriceCommand(sku, selling, mrp, Currency.INR, null, null, "seed", null));
    }

    private long rebuildItems() {
        return db.getCollection("work_queue").countDocuments(Filters.eq("type", ProjectionRebuildQueue.TYPE));
    }

    private Document rebuildItem(String sku) {
        return db.getCollection("work_queue").find(Filters.eq("_id", "card_rebuild:" + sku)).first();
    }

    private Document row(String sku) {
        return db.getCollection("product_card_base").find(Filters.eq("sku_id", sku)).first();
    }

    // ---------- Pricing batch read ----------

    @Test void pricing_batch_reports_active_missing_inactive_and_dedups_in_one_query() {
        seedPrice("TZP-B1", 10000, 12000);
        seedPrice("TZP-B2", 5000, 5000);
        seedPrice("TZP-B3", 7000, 9000);
        db.getCollection("price_current").updateOne(Filters.eq("sku_id", "TZP-B3"),
                new Document("$set", new Document("active", false)));

        var out = pricingWithQueue().findCurrentPrices(
                List.of("TZP-B1", "TZP-B2", "TZP-B3", "TZP-GHOST", "TZP-B1"), Currency.INR);
        assertEquals(4, out.size(), "duplicates deduplicated");
        assertEquals(PriceStatus.ACTIVE, out.get("TZP-B1").status());
        assertEquals(PriceStatus.ACTIVE, out.get("TZP-B2").status());
        assertEquals(PriceStatus.INACTIVE, out.get("TZP-B3").status());
        assertEquals(PriceStatus.MISSING, out.get("TZP-GHOST").status(), "absent SKU is MISSING, never dropped");
    }

    @Test void pricing_batch_validation_parity() {
        PricingService svc = pricingWithQueue();
        assertThrows(NullPointerException.class, () -> svc.findCurrentPrices(null, Currency.INR));
        assertThrows(IllegalArgumentException.class, () -> svc.findCurrentPrices(List.of("TZP-X", " "), Currency.INR));
        assertTrue(svc.findCurrentPrices(List.of(), Currency.INR).isEmpty());
    }

    // ---------- source-mutation enqueue hooks ----------

    @Test void price_write_enqueues_rebuild_idempotently() {
        seedProduct("TZP-P1", "active");
        PricingService svc = pricingWithQueue();
        svc.upsertPrice(new UpsertPriceCommand("TZP-P1", 10000, 12000, Currency.INR, null, null, "s", null));
        long v = svc.upsertPrice(new UpsertPriceCommand("TZP-P1", 11000, 12000, Currency.INR, null, null, "s", 1L));
        assertEquals(2L, v);
        assertNotNull(rebuildItem("TZP-P1"), "price write enqueued a rebuild");
        assertEquals(1, rebuildItems(), "two price writes for one SKU collapse to ONE pending request");
        assertEquals(ProjectionRebuildQueue.TYPE, rebuildItem("TZP-P1").getString("type"));
        assertEquals("price", rebuildItem("TZP-P1").getString("reason"));
    }

    @Test void media_write_enqueues_rebuild_for_owner() {
        mediaWithQueue().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.SKU, "TZP-M1",
                List.of(new MediaAsset("a1", "m/x.webp", ImageRole.PRIMARY, 0, "alt", 800, 600, "image/webp")),
                "s", null));
        assertNotNull(rebuildItem("TZP-M1"));
        assertEquals("media", rebuildItem("TZP-M1").getString("reason"));
    }

    @Test void catalog_write_enqueues_rebuild_through_the_single_write_path() {
        seedProduct("TZP-C1", "active");
        catalogWithQueue().updateTitle("TZP-C1", 1, "Renamed C1");
        assertNotNull(rebuildItem("TZP-C1"), "the WritePath chokepoint enqueues for every products mutation");
        assertEquals("catalog", rebuildItem("TZP-C1").getString("reason"));
    }

    @Test void unwired_services_never_enqueue() {
        seedProduct("TZP-U1", "active");
        // 3-arg constructors: no queue -> no work_queue pollution (protects the 897 existing tests).
        new PricingService(new Tx(client), new WritePath(db), CLOCK)
                .upsertPrice(new UpsertPriceCommand("TZP-U1", 10000, 12000, Currency.INR, null, null, "s", null));
        assertEquals(0, rebuildItems(), "no queue wired -> no enqueue");
    }

    // ---------- worker ----------

    @Test void worker_drains_request_and_builds_projection_then_removes_item() {
        seedProduct("TZP-W1", "active");
        seedPrice("TZP-W1", 10000, 12000);
        queue().requestRebuild("TZP-W1", "test");
        assertEquals(1, worker().drain(50));
        assertNotNull(row("TZP-W1"), "worker built the projection row via rebuildOne");
        assertEquals(0, rebuildItems(), "completed request removed");
    }

    @Test void worker_failure_is_isolated_and_item_left_for_retry() {
        seedProduct("TZP-OK", "active"); seedPrice("TZP-OK", 100, 200);
        seedProduct("TZP-POISON", "active"); seedPrice("TZP-POISON", 100, 200);
        queue().requestRebuild("TZP-OK", "test");
        queue().requestRebuild("TZP-POISON", "test");
        ProductCardProjectionService throwing = new ProductCardProjectionService(new CatalogCardReader(db),
                new PricingService(new Tx(client), new WritePath(db), CLOCK),
                new MediaService(new Tx(client), new WritePath(db), CLOCK), db, CLOCK) {
            @Override public RebuildOutcome rebuildOne(String skuId) {
                if ("TZP-POISON".equals(skuId)) throw new RuntimeException("boom");
                return super.rebuildOne(skuId);
            }
        };
        int processed = new ProjectionRebuildWorker(db, throwing, CLOCK).drain(50);
        assertEquals(1, processed, "poison SKU did not abort the batch");
        assertNotNull(row("TZP-OK"), "healthy SKU still built");
        assertNotNull(rebuildItem("TZP-POISON"), "failed item left for retry (lease expiry)");
    }

    @Test void two_concurrent_workers_do_not_double_process_one_item() throws Exception {
        seedProduct("TZP-CC", "active"); seedPrice("TZP-CC", 100, 200);
        queue().requestRebuild("TZP-CC", "test");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger total = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            fs.add(pool.submit(() -> {
                try {
                    start.await();
                    total.addAndGet(worker().drain(10));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdownNow();
        assertEquals(1, total.get(), "the lease claim ensures exactly one worker processes the item");
        assertNotNull(row("TZP-CC"));
        assertEquals(0, rebuildItems());
    }

    // ---------- reconciliation ----------

    @Test void reconcile_missing_enqueues_uncovered_eligible_products() {
        seedProduct("TZP-R1", "active"); seedPrice("TZP-R1", 100, 200); // eligible, no projection row
        assertNull(row("TZP-R1"));
        int enqueued = reconciler().reconcileMissing(100);
        assertTrue(enqueued >= 1);
        assertNotNull(rebuildItem("TZP-R1"));
        worker().drain(100);
        assertNotNull(row("TZP-R1"), "reconciliation backfilled the missing row");
    }

    @Test void reconcile_orphan_enqueues_ineligible_projection_rows_for_removal() {
        seedProduct("TZP-R2", "active"); seedPrice("TZP-R2", 100, 200);
        queue().requestRebuild("TZP-R2", "test");
        worker().drain(10);
        assertNotNull(row("TZP-R2"));
        // product becomes ineligible AFTER the row was built (drift the hooks could miss)
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-R2"),
                new Document("$set", new Document("lifecycle", "discontinued")));
        int enqueued = reconciler().reconcileOrphans(100);
        assertTrue(enqueued >= 1);
        worker().drain(10);
        assertNull(row("TZP-R2"), "orphan row removed by the worker's version-CAS delete");
    }

    @Test void reconcile_missing_is_bounded_by_limit() {
        for (int i = 0; i < 5; i++) { seedProduct("TZP-RB" + i, "active"); seedPrice("TZP-RB" + i, 100, 200); }
        int enqueued = reconciler().reconcileMissing(2);
        assertTrue(enqueued <= 2, "a pass never enqueues more than the bound");
    }

    // ---------- no location contamination ----------

    @Test void queue_item_and_projection_carry_no_location_state() {
        seedProduct("TZP-NL", "active"); seedPrice("TZP-NL", 100, 200);
        queue().requestRebuild("TZP-NL", "test");
        Document item = rebuildItem("TZP-NL");
        worker().drain(10);
        Document projection = row("TZP-NL");
        List<String> forbidden = List.of("pin", "pincode", "service_area_id", "serviceareaid",
                "fulfillment_location_id", "fulfillmentlocationid", "on_hand", "reserved",
                "stock", "available", "eta", "buyable", "address");
        for (Document d : List.of(item, projection)) {
            String json = d.toJson().toLowerCase(java.util.Locale.ROOT);
            for (String bad : forbidden) {
                assertFalse(json.contains(bad), "location/stock leak in " + d.get("_id") + ": " + bad);
            }
        }
    }

    // ---------- price overlay end-to-end (never persisted) ----------

    @Test void current_price_overlay_uses_fresh_price_without_touching_the_stored_row() {
        seedProduct("TZP-OV", "active");
        seedPrice("TZP-OV", 10000, 12000);
        queue().requestRebuild("TZP-OV", "test");
        worker().drain(10);
        long v1PriceVersion = ((Number) row("TZP-OV").get("source_versions", Document.class)
                .get("price_version")).longValue();
        // canonical price moves; the projection row is intentionally NOT rebuilt here
        pricingWithQueue().upsertPrice(new UpsertPriceCommand("TZP-OV", 8000, 12000,
                Currency.INR, null, null, "s", 1L));

        var base = new com.tazzzo.commerce.read.ProductCardBaseReader(db).findBySku("TZP-OV").orElseThrow();
        assertEquals(10000L, base.sellingPricePaise(), "stored projection still holds the old price");
        PriceLookup fresh = new PricingService(new Tx(client), new WritePath(db), CLOCK).findCurrentPrice("TZP-OV");
        var overlaid = com.tazzzo.commerce.read.CurrentPriceOverlay.withCurrentPrice(base, fresh);
        assertEquals(8000L, overlaid.sellingPricePaise(), "overlay serves the fresh canonical price");
        assertEquals(v1PriceVersion + 1, overlaid.priceVersion());
        // and the persisted row is untouched by the overlay
        assertEquals(10000L, ((Number) db.getCollection("product_card_base")
                .find(Filters.eq("sku_id", "TZP-OV")).first().get("selling_price_paise")).longValue());
    }
}
