package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.StockState;
import com.tazzzo.inventory.InvalidInventoryException;
import com.tazzzo.inventory.InventoryConflictException;
import com.tazzzo.inventory.InventoryLookup;
import com.tazzzo.inventory.InventoryNotFoundException;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-04 Inventory foundation — Testcontainers integration against Mongo 7 (replica set).
 * Proves canonical persistence, key uniqueness, derived stock state, CAS + rollback, atomic
 * oversell-safe reservation under real concurrency, and missing/inactive/zero distinctions.
 */
class InventoryFoundationIT extends AbstractMongoIT {

    private static final String LOC = "FL-BLR-01";
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private InventoryService service() {
        return new InventoryService(new Tx(client), new WritePath(db), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SetInventoryCommand create(String sku, String loc, long onHand, long threshold) {
        return new SetInventoryCommand(sku, loc, onHand, threshold, 10, "seed", null);
    }

    @Test void create_and_read_present_with_derived_state() {
        InventoryService svc = service();
        assertEquals(1L, svc.setInventory(create("TZP-INV1", LOC, 50, 5)));
        InventoryLookup lk = svc.findInventory("TZP-INV1", LOC);
        assertTrue(lk.isPresent());
        assertEquals(50, lk.record().onHand());
        assertEquals(0, lk.record().reserved());
        assertEquals(50, lk.record().available());
        assertEquals(StockState.IN_STOCK, lk.record().stockState());
        assertEquals(1, lk.record().version());
    }

    @Test void bson_quantities_are_int64() {
        service().setInventory(create("TZP-BSON2", LOC, 50, 5));
        Document d = db.getCollection("inventory").find(Filters.eq("sku_id", "TZP-BSON2")).first();
        assertInstanceOf(Long.class, d.get("on_hand"));
        assertInstanceOf(Long.class, d.get("reserved"));
        assertInstanceOf(Long.class, d.get("low_stock_threshold"));
        assertInstanceOf(Long.class, d.get("max_purchasable"));
        assertInstanceOf(Long.class, d.get("version"));
    }

    @Test void duplicate_create_rejected_by_unique_key() {
        InventoryService svc = service();
        svc.setInventory(create("TZP-DUP2", LOC, 10, 2));
        assertThrows(InventoryConflictException.class, () -> svc.setInventory(create("TZP-DUP2", LOC, 20, 2)));
    }

    @Test void cas_update_increments_version_and_stale_writer_rolls_back() {
        InventoryService svc = service();
        svc.setInventory(create("TZP-CAS2", LOC, 10, 2)); // v1
        long v2 = svc.setInventory(new SetInventoryCommand("TZP-CAS2", LOC, 25, 2, 10, "sync", 1L));
        assertEquals(2L, v2);

        long auditBefore = db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-CAS2"), Filters.eq("type", "INVENTORY_SET")));
        assertThrows(InventoryConflictException.class,
                () -> svc.setInventory(new SetInventoryCommand("TZP-CAS2", LOC, 99, 2, 10, "sync", 1L)));
        // state unchanged, and the conflicting write left NO audit residue (transaction rollback)
        InventoryLookup lk = svc.findInventory("TZP-CAS2", LOC);
        assertEquals(25, lk.record().onHand());
        assertEquals(2, lk.record().version());
        assertEquals(auditBefore, db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-CAS2"), Filters.eq("type", "INVENTORY_SET"))));
    }

    @Test void update_of_missing_row_is_not_found() {
        assertThrows(InventoryNotFoundException.class,
                () -> service().setInventory(new SetInventoryCommand("TZP-GHOST", LOC, 5, 1, 10, "sync", 1L)));
    }

    @Test void on_hand_below_live_reserved_rejected() {
        InventoryService svc = service();
        svc.setInventory(create("TZP-RESV", LOC, 10, 2)); // v1
        assertTrue(svc.tryReserve("TZP-RESV", LOC, 6));   // reserved=6, v2
        assertThrows(InvalidInventoryException.class,
                () -> svc.setInventory(new SetInventoryCommand("TZP-RESV", LOC, 5, 2, 10, "sync", 2L)));
        // untouched by the rejected write
        InventoryLookup lk = svc.findInventory("TZP-RESV", LOC);
        assertEquals(10, lk.record().onHand());
        assertEquals(6, lk.record().reserved());
    }

    @Test void rows_are_independent_across_locations_and_skus() {
        InventoryService svc = service();
        svc.setInventory(create("TZP-IND", "FL-BLR-01", 10, 2));
        svc.setInventory(create("TZP-IND", "FL-BLR-02", 2, 2)); // same SKU, other store: available==threshold -> LOW
        svc.setInventory(create("TZP-IND2", "FL-BLR-01", 0, 2)); // other SKU, same store

        assertEquals(StockState.IN_STOCK, svc.findInventory("TZP-IND", "FL-BLR-01").record().stockState());
        assertEquals(StockState.LOW_STOCK, svc.findInventory("TZP-IND", "FL-BLR-02").record().stockState());
        assertEquals(StockState.OUT_OF_STOCK, svc.findInventory("TZP-IND2", "FL-BLR-01").record().stockState());

        assertTrue(svc.tryReserve("TZP-IND", "FL-BLR-01", 5));
        // the other location's row is untouched by the reservation
        assertEquals(0, svc.findInventory("TZP-IND", "FL-BLR-02").record().reserved());
    }

    @Test void missing_is_distinguished_from_zero_stock_and_inactive() {
        InventoryService svc = service();
        svc.setInventory(create("TZP-ZERO", LOC, 0, 2));
        assertEquals(InventoryLookup.Status.PRESENT, svc.findInventory("TZP-ZERO", LOC).status());
        assertEquals(StockState.OUT_OF_STOCK, svc.findInventory("TZP-ZERO", LOC).record().stockState());

        assertEquals(InventoryLookup.Status.MISSING, svc.findInventory("TZP-NEVER", LOC).status());

        db.getCollection("inventory").updateOne(Filters.eq("sku_id", "TZP-ZERO"),
                new Document("$set", new Document("active", false)));
        assertEquals(InventoryLookup.Status.INACTIVE, svc.findInventory("TZP-ZERO", LOC).status());
    }

    @Test void reserve_succeeds_and_insufficient_reserve_leaves_no_residue() {
        InventoryService svc = service();
        svc.setInventory(create("TZP-RES2", LOC, 5, 1)); // v1, available 5
        assertTrue(svc.tryReserve("TZP-RES2", LOC, 3));  // available 2, v2

        long auditBefore = db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-RES2"), Filters.eq("type", "INVENTORY_RESERVED")));
        assertFalse(svc.tryReserve("TZP-RES2", LOC, 3)); // only 2 available -> refused
        InventoryLookup lk = svc.findInventory("TZP-RES2", LOC);
        assertEquals(3, lk.record().reserved());
        assertEquals(2, lk.record().version());
        // failed reservation left NO audit event (transaction rolled back)
        assertEquals(auditBefore, db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-RES2"), Filters.eq("type", "INVENTORY_RESERVED"))));
    }

    @Test void concurrent_reservations_cannot_oversell() throws Exception {
        InventoryService svc = service();
        svc.setInventory(create("TZP-RACE", LOC, 5, 1)); // available 5; two writers want 5 each
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable attempt = () -> {
                try {
                    start.await();
                    if (svc.tryReserve("TZP-RACE", LOC, 5)) successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<?> a = pool.submit(attempt);
            Future<?> b = pool.submit(attempt);
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, successes.get(), "exactly one of two concurrent 5-unit reservations may win");
        InventoryLookup lk = svc.findInventory("TZP-RACE", LOC);
        assertEquals(5, lk.record().reserved());
        assertEquals(0, lk.record().available());
        assertEquals(StockState.OUT_OF_STOCK, lk.record().stockState());
    }

    @Test void concurrent_cas_writers_produce_exactly_one_winner() throws Exception {
        InventoryService svc = service();
        svc.setInventory(create("TZP-CASRACE", LOC, 10, 2)); // v1
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable attempt = () -> {
                try {
                    start.await();
                    svc.setInventory(new SetInventoryCommand("TZP-CASRACE", LOC, 20, 2, 10, "sync", 1L));
                    wins.incrementAndGet();
                } catch (InventoryConflictException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<?> a = pool.submit(attempt);
            Future<?> b = pool.submit(attempt);
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "exactly one writer may advance v1 -> v2");
        assertEquals(1, conflicts.get(), "the stale writer gets a typed conflict");
        assertEquals(2, service().findInventory("TZP-CASRACE", LOC).record().version());
    }

    @Test void bootstrap_idempotent_and_inventory_unique_index_present() {
        assertDoesNotThrow(() -> schemaBootstrap.bootstrap(db));
        boolean unique = false;
        for (Document ix : db.getCollection("inventory").listIndexes()) {
            Document key = (Document) ix.get("key");
            if (key != null && key.containsKey("sku_id") && key.containsKey("fulfillment_location_id")) {
                unique = Boolean.TRUE.equals(ix.getBoolean("unique"));
            }
        }
        assertTrue(unique, "(sku_id, fulfillment_location_id) unique index present");
    }
}
