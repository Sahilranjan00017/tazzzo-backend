package com.tazzzo.inventory;

import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cancellation restock primitive: only a CONSUMED reservation, exactly once, all-or-nothing, never rewriting history. */
@SpringBootTest(classes = CatalogApplication.class)
class InventoryRestockIT extends AbstractMongoIT {

    private static final String LOC = "FL-RESTOCK-1";
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private Tx tx() {
        return new Tx(client);
    }

    /** Every reading is later than the last: the exactly-once guarantee must not depend on two calls sharing an instant. */
    private final java.util.concurrent.atomic.AtomicLong ticks = new java.util.concurrent.atomic.AtomicLong();
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW.plusSeconds(ticks.incrementAndGet()); }
    };

    private InventoryReservationService service() {
        InventoryReservationProperties p = new InventoryReservationProperties();
        p.setTtlSeconds(600);
        p.setExpiryBatchSize(100);
        return new InventoryReservationService(new InventoryService(tx(), new WritePath(db), clock), new InventoryReservationRepository(db), p,
                new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), clock, tx());
    }

    @BeforeEach
    void clean() {
        db.getCollection("inventory_reservations").deleteMany(new Document());
        db.getCollection("inventory").deleteMany(new Document());
    }

    private void seed(String sku, long onHand) {
        db.getCollection("inventory").insertOne(new Document("sku_id", sku).append("fulfillment_location_id", LOC).append("on_hand", onHand)
                .append("reserved", 0L).append("low_stock_threshold", 1L).append("max_purchasable", 100L).append("version", 1L)
                .append("active", true).append("source", "seed").append("created_at", new Date()).append("updated_at", new Date()));
    }

    private long onHand(String sku) {
        return db.getCollection("inventory").find(new Document("sku_id", sku).append("fulfillment_location_id", LOC)).first().get("on_hand", Number.class).longValue();
    }

    private InventoryReservation consumed(String order, String sku, int qty) {
        InventoryReservation r = service().reserve(order, LOC, List.of(new InventoryReservationItem(sku, qty)));
        return service().consume(r.reservationId() == null ? null : new InventoryReservationId(r.reservationId()));
    }

    private boolean restock(String reservationId) {
        return tx().call(s -> service().restockConsumed(s, new InventoryReservationId(reservationId)));
    }

    @Test
    void a_consumed_reservation_is_restocked_exactly_once_and_its_status_is_not_rewritten() {
        seed("TZP-R1", 10);
        InventoryReservation c = consumed("ORD-R1", "TZP-R1", 4);
        assertThat(onHand("TZP-R1")).isEqualTo(6);
        assertThat(restock(c.reservationId())).isTrue();
        assertThat(onHand("TZP-R1")).isEqualTo(10);
        assertThat(restock(c.reservationId())).as("the second call changes nothing").isFalse();
        assertThat(onHand("TZP-R1")).isEqualTo(10);
        Document header = db.getCollection("inventory_reservations").find(new Document("_id", c.reservationId())).first();
        assertThat(header.getString("status")).isEqualTo("CONSUMED");
        assertThat(header.getDate("restockedAt")).isNotNull();
    }

    @Test
    void every_line_of_a_multi_sku_reservation_is_returned() {
        seed("TZP-RA", 10);
        seed("TZP-RB", 10);
        InventoryReservation r = service().reserve("ORD-RM", LOC, List.of(new InventoryReservationItem("TZP-RA", 3), new InventoryReservationItem("TZP-RB", 5)));
        InventoryReservation c = service().consume(new InventoryReservationId(r.reservationId()));
        assertThat(restock(c.reservationId())).isTrue();
        assertThat(onHand("TZP-RA")).isEqualTo(10);
        assertThat(onHand("TZP-RB")).isEqualTo(10);
    }

    @Test
    void only_a_consumed_reservation_can_be_restocked() {
        seed("TZP-R2", 10);
        InventoryReservation held = service().reserve("ORD-R2", LOC, List.of(new InventoryReservationItem("TZP-R2", 2)));
        assertThatThrownBy(() -> restock(held.reservationId())).isInstanceOf(InventoryReservationFailure.class)
                .extracting(e -> ((InventoryReservationFailure) e).reason()).isEqualTo(InventoryReservationFailure.Reason.INVALID_TRANSITION);
        service().release(new InventoryReservationId(held.reservationId()));
        assertThatThrownBy(() -> restock(held.reservationId())).isInstanceOf(InventoryReservationFailure.class);
        assertThatThrownBy(() -> restock("RSV_does-not-exist-0000")).isInstanceOf(RuntimeException.class);
        assertThat(onHand("TZP-R2")).isEqualTo(10);
    }

    @Test
    void a_row_that_cannot_take_the_units_back_aborts_everything_including_the_marker() {
        seed("TZP-R3", 10);
        InventoryReservation c = consumed("ORD-R3", "TZP-R3", 4);
        db.getCollection("inventory").updateOne(new Document("sku_id", "TZP-R3"), new Document("$set", new Document("on_hand", 999_999L)));   // 999_999 + 4 > the ceiling
        assertThatThrownBy(() -> restock(c.reservationId())).isInstanceOf(InventoryReservationFailure.class)
                .extracting(e -> ((InventoryReservationFailure) e).reason()).isEqualTo(InventoryReservationFailure.Reason.INTEGRITY_FAILURE);
        assertThat(db.getCollection("inventory_reservations").find(new Document("_id", c.reservationId())).first().containsKey("restockedAt"))
                .as("the marker rolled back with the failed restock").isFalse();
        assertThat(onHand("TZP-R3")).isEqualTo(999_999L);
    }

    @Test
    void concurrent_restocks_return_the_units_exactly_once() throws Exception {
        seed("TZP-R4", 10);
        InventoryReservation c = consumed("ORD-R4", "TZP-R4", 4);
        var pool = Executors.newFixedThreadPool(6);
        var go = new CountDownLatch(1);
        java.util.List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    return restock(c.reservationId());
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int applied = 0;
        for (var f : futures) if (f.get()) applied++;
        pool.shutdown();
        assertThat(applied).isEqualTo(1);
        assertThat(onHand("TZP-R4")).isEqualTo(10);
    }
}
