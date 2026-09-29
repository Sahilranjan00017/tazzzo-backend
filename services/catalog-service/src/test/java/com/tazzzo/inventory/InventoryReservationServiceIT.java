package com.tazzzo.inventory;

import com.mongodb.client.ClientSession;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-14A — the production reservation lifecycle against a real Mongo transaction (Testcontainers):
 * multi-SKU all-or-nothing, retry safety, idempotency, release/consume, expiry, and session-aware
 * composition with an OUTER caller-owned transaction. No sleeps: concurrency uses latches; retries
 * use {@link RetryInjectingTx} (a real transaction whose commit "fails transiently").
 */
@SpringBootTest(classes = CatalogApplication.class)
class InventoryReservationServiceIT extends AbstractMongoIT {

    private static final String LOC = "FL-RESV-1";
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private InventoryService inventory(Tx tx, Clock clock) {
        return new InventoryService(tx, new WritePath(db), clock);
    }

    private InventoryReservationProperties properties() {
        InventoryReservationProperties p = new InventoryReservationProperties();
        p.setTtlSeconds(600);
        p.setExpiryBatchSize(100);
        return p;
    }

    private InventoryReservationService service(Tx tx, Clock clock) {
        return new InventoryReservationService(inventory(tx, clock), new InventoryReservationRepository(db),
                properties(), clock, tx);
    }

    private InventoryReservationService service() {
        return service(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seed(String sku, String loc, long onHand, boolean active) {
        db.getCollection("inventory").insertOne(new Document("sku_id", sku)
                .append("fulfillment_location_id", loc).append("on_hand", onHand).append("reserved", 0L)
                .append("low_stock_threshold", 1L).append("max_purchasable", 100L).append("version", 1L)
                .append("active", active).append("source", "seed").append("created_at", new Date())
                .append("updated_at", new Date()));
    }

    private static java.util.Date date() {
        return java.util.Date.from(NOW);
    }

    private Document inventoryRow(String sku, String loc) {
        return db.getCollection("inventory").find(new Document("sku_id", sku)
                .append("fulfillment_location_id", loc)).first();
    }

    private long reserved(String sku, String loc) {
        return inventoryRow(sku, loc).get("reserved", Number.class).longValue();
    }

    private long onHand(String sku, String loc) {
        return inventoryRow(sku, loc).get("on_hand", Number.class).longValue();
    }

    private static InventoryReservationCommand command(String orderId, List<InventoryReservationItem> items) {
        return new InventoryReservationCommand(orderId, LOC, items, InventoryReservationId.generate(),
                NOW.plusSeconds(600));
    }

    // ---------- multi-SKU reserve: all-or-nothing ----------

    @Test void single_sku_reserve_succeeds_and_increments_reserved() {
        seed("TZP-1", LOC, 10, true);
        InventoryReservation r = service().reserve("ORD-1", LOC, List.of(new InventoryReservationItem("TZP-1", 4)));
        assertThat(r.status()).isEqualTo(InventoryReservationStatus.RESERVED);
        assertThat(reserved("TZP-1", LOC)).isEqualTo(4);
    }

    @Test void multi_sku_reserve_succeeds_and_every_line_increments() {
        seed("TZP-A", LOC, 10, true);
        seed("TZP-B", LOC, 10, true);
        InventoryReservation r = service().reserve("ORD-MULTI", LOC,
                List.of(new InventoryReservationItem("TZP-A", 3), new InventoryReservationItem("TZP-B", 5)));
        assertThat(r.items()).hasSize(2);
        assertThat(reserved("TZP-A", LOC)).isEqualTo(3);
        assertThat(reserved("TZP-B", LOC)).isEqualTo(5);
    }

    @Test void one_insufficient_sku_rolls_back_every_earlier_increment() {
        seed("TZP-OK1", LOC, 10, true);
        seed("TZP-OK2", LOC, 10, true);
        seed("TZP-SHORT", LOC, 2, true); // sorts last alphabetically -> fails after two earlier lines applied
        InventoryReservationService svc = service();
        assertThatThrownBy(() -> svc.reserve("ORD-PARTIAL", LOC, List.of(
                new InventoryReservationItem("TZP-OK1", 5), new InventoryReservationItem("TZP-OK2", 5),
                new InventoryReservationItem("TZP-SHORT", 5))))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.RESERVATION_UNAVAILABLE));
        assertThat(reserved("TZP-OK1", LOC)).as("earlier line rolled back").isZero();
        assertThat(reserved("TZP-OK2", LOC)).as("earlier line rolled back").isZero();
        assertThat(reserved("TZP-SHORT", LOC)).isZero();
        assertThat(svc.findByOrderId("ORD-PARTIAL")).as("no header survives").isEmpty();
    }

    @Test void a_missing_sku_rolls_back_the_entire_reservation() {
        seed("TZP-PRESENT", LOC, 10, true);
        InventoryReservationService svc = service();
        assertThatThrownBy(() -> svc.reserve("ORD-MISSING", LOC, List.of(
                new InventoryReservationItem("TZP-PRESENT", 3), new InventoryReservationItem("TZP-NOROW", 1))))
                .isInstanceOf(InventoryReservationFailure.class);
        assertThat(reserved("TZP-PRESENT", LOC)).isZero();
        assertThat(svc.findByOrderId("ORD-MISSING")).isEmpty();
    }

    @Test void an_inactive_sku_rolls_back_the_entire_reservation() {
        seed("TZP-ACTIVE", LOC, 10, true);
        seed("TZP-OFF", LOC, 10, false);
        InventoryReservationService svc = service();
        assertThatThrownBy(() -> svc.reserve("ORD-INACTIVE", LOC, List.of(
                new InventoryReservationItem("TZP-ACTIVE", 3), new InventoryReservationItem("TZP-OFF", 1))))
                .isInstanceOf(InventoryReservationFailure.class);
        assertThat(reserved("TZP-ACTIVE", LOC)).isZero();
    }

    // ---------- idempotency ----------

    @Test void same_order_same_input_returns_the_same_durable_reservation() {
        seed("TZP-IDEM", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation first = svc.reserve("ORD-IDEM", LOC, List.of(new InventoryReservationItem("TZP-IDEM", 3)));
        InventoryReservation again = svc.reserve("ORD-IDEM", LOC, List.of(new InventoryReservationItem("TZP-IDEM", 3)));
        assertThat(again.reservationId()).isEqualTo(first.reservationId());
        assertThat(reserved("TZP-IDEM", LOC)).as("never re-incremented").isEqualTo(3);
    }

    @Test void same_order_different_input_is_a_typed_conflict() {
        seed("TZP-CONF", LOC, 10, true);
        InventoryReservationService svc = service();
        svc.reserve("ORD-CONF", LOC, List.of(new InventoryReservationItem("TZP-CONF", 3)));
        assertThatThrownBy(() -> svc.reserve("ORD-CONF", LOC, List.of(new InventoryReservationItem("TZP-CONF", 4))))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.ALREADY_RESERVED_DIFFERENT_INPUT));
        assertThat(reserved("TZP-CONF", LOC)).isEqualTo(3);
    }

    @Test void concurrent_same_order_same_input_converges_on_one_reservation() throws Exception {
        seed("TZP-RACE-IDEM", LOC, 10, true);
        InventoryReservationService svc = service();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<InventoryReservation>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return svc.reserve("ORD-RACE-IDEM", LOC, List.of(new InventoryReservationItem("TZP-RACE-IDEM", 2)));
                }));
            }
            go.countDown();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Future<InventoryReservation> f : calls) {
                ids.add(f.get(30, TimeUnit.SECONDS).reservationId());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reserved("TZP-RACE-IDEM", LOC)).as("stock incremented exactly once").isEqualTo(2);
    }

    @Test void concurrent_stock_competition_cannot_oversell() throws Exception {
        seed("TZP-OVERSELL", LOC, 10, true);
        InventoryReservationService svc = service();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int idx = i;
                calls.add(pool.submit(() -> {
                    go.await();
                    try {
                        svc.reserve("ORD-OVERSELL-" + idx, LOC, List.of(new InventoryReservationItem("TZP-OVERSELL", 3)));
                        return true;
                    } catch (InventoryReservationFailure e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            AtomicInteger successes = new AtomicInteger();
            for (Future<Boolean> f : calls) {
                if (f.get(30, TimeUnit.SECONDS)) successes.incrementAndGet();
            }
            assertThat(successes.get()).isEqualTo(3); // 10 / 3 = 3 successful reservations of 3 each
        } finally {
            pool.shutdownNow();
        }
        assertThat(reserved("TZP-OVERSELL", LOC)).isEqualTo(9);
        assertThat(onHand("TZP-OVERSELL", LOC) - reserved("TZP-OVERSELL", LOC)).as("available never negative")
                .isGreaterThanOrEqualTo(0);
    }

    // ---------- forced transaction retry ----------

    @Test void a_forced_retry_does_not_double_reserve_and_keeps_the_reservationId_and_expiresAt_stable() {
        seed("TZP-RETRY", LOC, 10, true);
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService svc = service(retryTx, clock);
        retryTx.arm(1);

        InventoryReservation r = svc.reserve("ORD-RETRY", LOC, List.of(new InventoryReservationItem("TZP-RETRY", 4)));

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(retryTx.attemptResults()).extracting(o -> ((InventoryReservation) o).reservationId())
                .as("reservationId stable across the retry").containsExactly(r.reservationId(), r.reservationId());
        assertThat(retryTx.attemptResults()).extracting(o -> ((InventoryReservation) o).expiresAt())
                .as("expiresAt stable across the retry").containsExactly(r.expiresAt(), r.expiresAt());
        assertThat(reserved("TZP-RETRY", LOC)).as("never double-incremented").isEqualTo(4);
    }

    @Test void a_retry_that_finds_its_own_committed_attempt_returns_it_without_reincrementing() {
        seed("TZP-AMBIG", LOC, 10, true);
        InventoryReservationCommand fixedCommand = command("ORD-AMBIG", List.of(new InventoryReservationItem("TZP-AMBIG", 3)));
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        InventoryReservationService svc = service(retryTx, Clock.fixed(NOW, ZoneOffset.UTC));
        // simulate the "ambiguous commit" case: force a second attempt even though attempt 1 fully
        // committed (RetryInjectingTx re-runs the callback regardless of what the DB now shows).
        retryTx.arm(1);
        InventoryReservation r = svc.reserve(fixedCommand.orderId(), LOC, fixedCommand.items());
        assertThat(retryTx.attemptResults()).extracting(o -> ((InventoryReservation) o).reservationId())
                .containsExactly(r.reservationId(), r.reservationId());
        assertThat(reserved("TZP-AMBIG", LOC)).isEqualTo(3);
    }

    // ---------- release ----------

    @Test void release_decrements_every_reserved_counter() {
        seed("TZP-REL-A", LOC, 10, true);
        seed("TZP-REL-B", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation r = svc.reserve("ORD-REL", LOC,
                List.of(new InventoryReservationItem("TZP-REL-A", 4), new InventoryReservationItem("TZP-REL-B", 6)));
        InventoryReservation released = svc.release(new InventoryReservationId(r.reservationId()));
        assertThat(released.status()).isEqualTo(InventoryReservationStatus.RELEASED);
        assertThat(reserved("TZP-REL-A", LOC)).isZero();
        assertThat(reserved("TZP-REL-B", LOC)).isZero();
    }

    @Test void release_is_idempotent_and_consume_after_release_is_rejected() {
        seed("TZP-REL2", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation r = svc.reserve("ORD-REL2", LOC, List.of(new InventoryReservationItem("TZP-REL2", 4)));
        InventoryReservationId id = new InventoryReservationId(r.reservationId());
        svc.release(id);
        InventoryReservation second = svc.release(id); // idempotent no-op
        assertThat(second.status()).isEqualTo(InventoryReservationStatus.RELEASED);
        assertThat(reserved("TZP-REL2", LOC)).isZero();
        assertThatThrownBy(() -> svc.consume(id)).isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.INVALID_TRANSITION));
    }

    // ---------- consume ----------

    @Test void consume_decrements_both_reserved_and_on_hand() {
        seed("TZP-CON", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation r = svc.reserve("ORD-CON", LOC, List.of(new InventoryReservationItem("TZP-CON", 4)));
        InventoryReservation consumed = svc.consume(new InventoryReservationId(r.reservationId()));
        assertThat(consumed.status()).isEqualTo(InventoryReservationStatus.CONSUMED);
        assertThat(reserved("TZP-CON", LOC)).isZero();
        assertThat(onHand("TZP-CON", LOC)).isEqualTo(6);
    }

    @Test void consume_is_idempotent_and_release_after_consume_is_rejected() {
        seed("TZP-CON2", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation r = svc.reserve("ORD-CON2", LOC, List.of(new InventoryReservationItem("TZP-CON2", 4)));
        InventoryReservationId id = new InventoryReservationId(r.reservationId());
        svc.consume(id);
        InventoryReservation second = svc.consume(id); // idempotent no-op
        assertThat(second.status()).isEqualTo(InventoryReservationStatus.CONSUMED);
        assertThat(onHand("TZP-CON2", LOC)).isEqualTo(6);
        assertThatThrownBy(() -> svc.release(id)).isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.INVALID_TRANSITION));
        assertThat(onHand("TZP-CON2", LOC)).as("consume never applied twice").isEqualTo(6);
    }

    @Test void not_found_reservation_is_a_typed_failure() {
        InventoryReservationService svc = service();
        assertThatThrownBy(() -> svc.release(InventoryReservationId.generate()))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.NOT_FOUND));
    }

    // ---------- expiry ----------

    @Test void expiry_worker_releases_an_expired_reservation_and_ignores_an_active_one() {
        seed("TZP-EXP", LOC, 10, true);
        seed("TZP-FUT", LOC, 10, true);
        Clock past = Clock.fixed(NOW.minusSeconds(700), ZoneOffset.UTC);
        InventoryReservationService pastSvc = service(new Tx(client), past);
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService expiredMaker = new InventoryReservationService(inventory(new Tx(client), past),
                new InventoryReservationRepository(db), shortTtl, past, new Tx(client));
        InventoryReservation expired = expiredMaker.reserve("ORD-EXP", LOC, List.of(new InventoryReservationItem("TZP-EXP", 2)));

        InventoryReservationProperties longTtl = properties();
        InventoryReservationService futureMaker = new InventoryReservationService(inventory(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC)),
                new InventoryReservationRepository(db), longTtl, Clock.fixed(NOW, ZoneOffset.UTC), new Tx(client));
        InventoryReservation future = futureMaker.reserve("ORD-FUT", LOC, List.of(new InventoryReservationItem("TZP-FUT", 2)));

        Clock nowClock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService nowSvc = service(new Tx(client), nowClock);
        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), nowSvc,
                new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                nowClock);

        int released = worker.reconcileExpired(100);
        assertThat(released).isEqualTo(1);
        assertThat(nowSvc.findById(new InventoryReservationId(expired.reservationId())).orElseThrow().status())
                .isEqualTo(InventoryReservationStatus.RELEASED);
        assertThat(nowSvc.findById(new InventoryReservationId(future.reservationId())).orElseThrow().status())
                .as("an active future reservation is untouched").isEqualTo(InventoryReservationStatus.RESERVED);
        assertThat(reserved("TZP-EXP", LOC)).isZero();
        assertThat(reserved("TZP-FUT", LOC)).isEqualTo(2);
    }

    @Test void expiry_vs_explicit_release_race_is_safe_one_wins_the_other_no_ops() {
        seed("TZP-RACE-REL", LOC, 10, true);
        Clock past = Clock.fixed(NOW.minusSeconds(700), ZoneOffset.UTC);
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService maker = new InventoryReservationService(inventory(new Tx(client), past),
                new InventoryReservationRepository(db), shortTtl, past, new Tx(client));
        InventoryReservation r = maker.reserve("ORD-RACE-REL", LOC, List.of(new InventoryReservationItem("TZP-RACE-REL", 5)));

        Clock nowClock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService nowSvc = service(new Tx(client), nowClock);
        // explicit release wins first
        InventoryReservation released = nowSvc.release(new InventoryReservationId(r.reservationId()));
        assertThat(released.status()).isEqualTo(InventoryReservationStatus.RELEASED);

        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), nowSvc,
                new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                nowClock);
        int releasedByWorker = worker.reconcileExpired(100); // finds nothing: already RELEASED, not RESERVED
        assertThat(releasedByWorker).isZero();
        assertThat(reserved("TZP-RACE-REL", LOC)).as("decremented exactly once").isZero();
    }

    @Test void expiry_vs_consume_race_is_safe_consume_wins_no_double_decrement() {
        seed("TZP-RACE-CON", LOC, 10, true);
        Clock past = Clock.fixed(NOW.minusSeconds(700), ZoneOffset.UTC);
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService maker = new InventoryReservationService(inventory(new Tx(client), past),
                new InventoryReservationRepository(db), shortTtl, past, new Tx(client));
        InventoryReservation r = maker.reserve("ORD-RACE-CON", LOC, List.of(new InventoryReservationItem("TZP-RACE-CON", 5)));

        Clock nowClock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService nowSvc = service(new Tx(client), nowClock);
        InventoryReservation consumed = nowSvc.consume(new InventoryReservationId(r.reservationId()));
        assertThat(consumed.status()).isEqualTo(InventoryReservationStatus.CONSUMED);

        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), nowSvc,
                new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                nowClock);
        int releasedByWorker = worker.reconcileExpired(100); // finds nothing: already CONSUMED, not RESERVED
        assertThat(releasedByWorker).isZero();
        assertThat(onHand("TZP-RACE-CON", LOC)).as("decremented exactly once").isEqualTo(5);
    }

    // ---------- session-aware port participates in the CALLER's transaction ----------

    @Test void session_aware_reserve_participates_in_the_callers_transaction_outer_rollback_undoes_everything() {
        seed("TZP-OUTER", LOC, 10, true);
        Tx tx = new Tx(client);
        InventoryReservationService svc = service(tx, Clock.fixed(NOW, ZoneOffset.UTC));
        InventoryReservationCommand cmd = command("ORD-OUTER", List.of(new InventoryReservationItem("TZP-OUTER", 4)));

        class OuterAbort extends RuntimeException { }
        assertThatThrownBy(() -> tx.call(session -> {
            svc.reserve(session, cmd, NOW);
            throw new OuterAbort(); // the CALLER's own reason to roll back, unrelated to reservation logic
        })).isInstanceOf(OuterAbort.class);

        assertThat(reserved("TZP-OUTER", LOC)).as("no increment survives the outer rollback").isZero();
        assertThat(svc.findByOrderId("ORD-OUTER")).as("no reservation header survives").isEmpty();
    }

    @Test void session_aware_release_participates_in_the_callers_transaction_outer_rollback_undoes_it() {
        seed("TZP-OUTER-REL", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation r = svc.reserve("ORD-OUTER-REL", LOC, List.of(new InventoryReservationItem("TZP-OUTER-REL", 4)));

        Tx tx = new Tx(client);
        class OuterAbort extends RuntimeException { }
        assertThatThrownBy(() -> tx.call(session -> {
            svc.release(session, new InventoryReservationId(r.reservationId()), NOW);
            throw new OuterAbort();
        })).isInstanceOf(OuterAbort.class);

        assertThat(reserved("TZP-OUTER-REL", LOC)).as("release rolled back with the outer transaction").isEqualTo(4);
        assertThat(svc.findById(new InventoryReservationId(r.reservationId())).orElseThrow().status())
                .isEqualTo(InventoryReservationStatus.RESERVED);
    }

    @Test void session_aware_consume_participates_in_the_callers_transaction_outer_rollback_undoes_it() {
        seed("TZP-OUTER-CON", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation r = svc.reserve("ORD-OUTER-CON", LOC, List.of(new InventoryReservationItem("TZP-OUTER-CON", 4)));

        Tx tx = new Tx(client);
        class OuterAbort extends RuntimeException { }
        assertThatThrownBy(() -> tx.call(session -> {
            svc.consume(session, new InventoryReservationId(r.reservationId()), NOW);
            throw new OuterAbort();
        })).isInstanceOf(OuterAbort.class);

        assertThat(onHand("TZP-OUTER-CON", LOC)).as("consume rolled back with the outer transaction").isEqualTo(10);
        assertThat(reserved("TZP-OUTER-CON", LOC)).isEqualTo(4);
        assertThat(svc.findById(new InventoryReservationId(r.reservationId())).orElseThrow().status())
                .isEqualTo(InventoryReservationStatus.RESERVED);
    }

    @Test void a_future_order_composition_shape_reserves_and_inserts_together_in_one_transaction() {
        seed("TZP-COMPOSE", LOC, 10, true);
        Tx tx = new Tx(client);
        InventoryReservationService svc = service(tx, Clock.fixed(NOW, ZoneOffset.UTC));
        InventoryReservationCommand cmd = command("ORD-COMPOSE", List.of(new InventoryReservationItem("TZP-COMPOSE", 4)));

        // simulates: tx.call(session -> { reserve(session,...); orderRepository.insert(session,...); return order; })
        String result = tx.call(session -> {
            InventoryReservation r = svc.reserve(session, cmd, NOW);
            db.getCollection("inventory_reservations_order_marker").insertOne(session,
                    new Document("_id", cmd.orderId()).append("reservationId", r.reservationId()));
            return r.reservationId();
        });

        assertThat(reserved("TZP-COMPOSE", LOC)).isEqualTo(4);
        assertThat(db.getCollection("inventory_reservations_order_marker").find(new Document("_id", "ORD-COMPOSE"))
                .first().getString("reservationId")).isEqualTo(result);
    }
}
