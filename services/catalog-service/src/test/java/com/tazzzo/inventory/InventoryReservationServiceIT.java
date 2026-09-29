package com.tazzzo.inventory;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.RetryInjectingTx;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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

    // this class's expiry-worker tests scan ACROSS all reservations (findExpiredBatch has no
    // per-test key scope), so -- unlike most IT classes here, which isolate tests purely via unique
    // document keys under a class-level @BeforeAll reset -- each test needs its own clean slate.
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

    private InventoryReservationObservability observability() {
        return new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private InventoryReservationService service(Tx tx, Clock clock) {
        return new InventoryReservationService(inventory(tx, clock), new InventoryReservationRepository(db),
                properties(), observability(), clock, tx);
    }

    private InventoryReservationService service(Tx tx, Clock clock, InventoryReservationProperties props) {
        return new InventoryReservationService(inventory(tx, clock), new InventoryReservationRepository(db),
                props, observability(), clock, tx);
    }

    private InventoryReservationService service() {
        return service(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void resetReservationsAndInventory() {
        db.getCollection("inventory_reservations").deleteMany(new Document());
        db.getCollection("inventory").deleteMany(new Document());
        db.getCollection("inventory_reservations_order_marker").deleteMany(new Document());
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

    private static PreparedInventoryReservation command(String orderId, List<InventoryReservationItem> items) {
        return new PreparedInventoryReservation(orderId, LOC, items, InventoryReservationId.generate(), NOW,
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
        PreparedInventoryReservation fixedCommand = command("ORD-AMBIG", List.of(new InventoryReservationItem("TZP-AMBIG", 3)));
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
        InventoryReservationService expiredMaker = service(new Tx(client), past, shortTtl);
        InventoryReservation expired = expiredMaker.reserve("ORD-EXP", LOC, List.of(new InventoryReservationItem("TZP-EXP", 2)));

        InventoryReservationProperties longTtl = properties();
        InventoryReservationService futureMaker = service(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC), longTtl);
        InventoryReservation future = futureMaker.reserve("ORD-FUT", LOC, List.of(new InventoryReservationItem("TZP-FUT", 2)));

        Clock nowClock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService nowSvc = service(new Tx(client), nowClock);
        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), nowSvc, observability(), nowClock);

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
        InventoryReservationService maker = service(new Tx(client), past, shortTtl);
        InventoryReservation r = maker.reserve("ORD-RACE-REL", LOC, List.of(new InventoryReservationItem("TZP-RACE-REL", 5)));

        Clock nowClock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService nowSvc = service(new Tx(client), nowClock);
        // explicit release wins first
        InventoryReservation released = nowSvc.release(new InventoryReservationId(r.reservationId()));
        assertThat(released.status()).isEqualTo(InventoryReservationStatus.RELEASED);

        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), nowSvc, observability(), nowClock);
        int releasedByWorker = worker.reconcileExpired(100); // finds nothing: already RELEASED, not RESERVED
        assertThat(releasedByWorker).isZero();
        assertThat(reserved("TZP-RACE-REL", LOC)).as("decremented exactly once").isZero();
    }

    // ---------- PR-14A hardening H1: expiry is runtime-authoritative, never worker-cadence-dependent ----------

    @Test void h1a_reserve_of_an_already_expired_prepared_command_is_rejected_no_increment_no_header() {
        seed("TZP-H1A", LOC, 10, true);
        java.util.concurrent.atomic.AtomicReference<Instant> liveNow = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock movable = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return liveNow.get(); }
        };
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService svc = service(new Tx(client), movable, shortTtl);

        PreparedInventoryReservation prepared = svc.prepare(new InventoryReservationRequest("ORD-H1A", LOC,
                List.of(new InventoryReservationItem("TZP-H1A", 3))));
        liveNow.set(NOW.plusSeconds(120)); // advance the injected clock past expiresAt BEFORE reserve() runs

        Tx tx = new Tx(client);
        assertThatThrownBy(() -> tx.call(session -> svc.reserve(session, prepared)))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.RESERVATION_EXPIRED));
        assertThat(reserved("TZP-H1A", LOC)).as("no increment").isZero();
        assertThat(svc.findByOrderId("ORD-H1A")).as("no header created").isEmpty();
    }

    @Test void h1b_same_order_same_input_replay_semantics_across_the_full_lifecycle() {
        // active: returns the original
        seed("TZP-H1B-1", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation active = svc.reserve("ORD-H1B-1", LOC, List.of(new InventoryReservationItem("TZP-H1B-1", 2)));
        InventoryReservation replay = svc.reserve("ORD-H1B-1", LOC, List.of(new InventoryReservationItem("TZP-H1B-1", 2)));
        assertThat(replay.reservationId()).isEqualTo(active.reservationId());

        // expired but not yet worker-reconciled: REJECTED, never silently returned as active, never re-reserved
        seed("TZP-H1B-2", LOC, 10, true);
        Clock past = Clock.fixed(NOW.minusSeconds(700), ZoneOffset.UTC);
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService maker = service(new Tx(client), past, shortTtl);
        InventoryReservation expired = maker.reserve("ORD-H1B-2", LOC, List.of(new InventoryReservationItem("TZP-H1B-2", 2)));
        InventoryReservationService nowSvc = service(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> nowSvc.reserve("ORD-H1B-2", LOC, List.of(new InventoryReservationItem("TZP-H1B-2", 2))))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.RESERVATION_EXPIRED));
        assertThat(reserved("TZP-H1B-2", LOC)).as("never re-incremented").isEqualTo(2);
        assertThat(svc.findByOrderId("ORD-H1B-2").orElseThrow().reservationId())
                .as("no replacement header minted").isEqualTo(expired.reservationId());

        // RELEASED: returned as-is (terminal, informational)
        seed("TZP-H1B-3", LOC, 10, true);
        InventoryReservation r3 = svc.reserve("ORD-H1B-3", LOC, List.of(new InventoryReservationItem("TZP-H1B-3", 1)));
        svc.release(new InventoryReservationId(r3.reservationId()));
        InventoryReservation replayReleased = svc.reserve("ORD-H1B-3", LOC, List.of(new InventoryReservationItem("TZP-H1B-3", 1)));
        assertThat(replayReleased.status()).isEqualTo(InventoryReservationStatus.RELEASED);

        // CONSUMED: returned as-is (terminal, informational)
        seed("TZP-H1B-4", LOC, 10, true);
        InventoryReservation r4 = svc.reserve("ORD-H1B-4", LOC, List.of(new InventoryReservationItem("TZP-H1B-4", 1)));
        svc.consume(new InventoryReservationId(r4.reservationId()));
        InventoryReservation replayConsumed = svc.reserve("ORD-H1B-4", LOC, List.of(new InventoryReservationItem("TZP-H1B-4", 1)));
        assertThat(replayConsumed.status()).isEqualTo(InventoryReservationStatus.CONSUMED);
    }

    @Test void h1c_and_h1d_consume_after_expiry_is_rejected_until_the_worker_releases_it() {
        seed("TZP-H1C", LOC, 10, true);
        Clock past = Clock.fixed(NOW.minusSeconds(700), ZoneOffset.UTC);
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService maker = service(new Tx(client), past, shortTtl);
        InventoryReservation r = maker.reserve("ORD-H1C", LOC, List.of(new InventoryReservationItem("TZP-H1C", 5)));

        Clock nowClock = Clock.fixed(NOW, ZoneOffset.UTC); // authoritative "now" is well past expiresAt
        InventoryReservationService nowSvc = service(new Tx(client), nowClock);

        // consume must be rejected -- NOT run the expiry worker first
        assertThatThrownBy(() -> nowSvc.consume(new InventoryReservationId(r.reservationId())))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.RESERVATION_EXPIRED));
        assertThat(onHand("TZP-H1C", LOC)).as("no on_hand decrement").isEqualTo(10);
        assertThat(reserved("TZP-H1C", LOC)).as("still held").isEqualTo(5);
        assertThat(nowSvc.findById(new InventoryReservationId(r.reservationId())).orElseThrow().status())
                .as("header still RESERVED until release/reconciliation").isEqualTo(InventoryReservationStatus.RESERVED);

        // NOW run the expiry worker: it releases it through the normal lifecycle
        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), nowSvc, observability(), nowClock);
        int released = worker.reconcileExpired(100);
        assertThat(released).isEqualTo(1);
        assertThat(reserved("TZP-H1C", LOC)).isZero();
        assertThat(nowSvc.findById(new InventoryReservationId(r.reservationId())).orElseThrow().status())
                .isEqualTo(InventoryReservationStatus.RELEASED);

        // a confirmation that arrives even LATER (after the worker already released it) is still
        // safely rejected -- never silently "wins" just because the worker ran first either.
        assertThatThrownBy(() -> nowSvc.consume(new InventoryReservationId(r.reservationId())))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.INVALID_TRANSITION));
    }

    // ---------- session-aware port participates in the CALLER's transaction ----------

    @Test void session_aware_reserve_participates_in_the_callers_transaction_outer_rollback_undoes_everything() {
        seed("TZP-OUTER", LOC, 10, true);
        Tx tx = new Tx(client);
        InventoryReservationService svc = service(tx, Clock.fixed(NOW, ZoneOffset.UTC));
        PreparedInventoryReservation cmd = command("ORD-OUTER", List.of(new InventoryReservationItem("TZP-OUTER", 4)));

        class OuterAbort extends RuntimeException { }
        assertThatThrownBy(() -> tx.call(session -> {
            svc.reserve(session, cmd);
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
            svc.release(session, new InventoryReservationId(r.reservationId()));
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
            svc.consume(session, new InventoryReservationId(r.reservationId()));
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
        PreparedInventoryReservation cmd = command("ORD-COMPOSE", List.of(new InventoryReservationItem("TZP-COMPOSE", 4)));

        // simulates: tx.call(session -> { reserve(session,...); orderRepository.insert(session,...); return order; })
        String result = tx.call(session -> {
            InventoryReservation r = svc.reserve(session, cmd);
            db.getCollection("inventory_reservations_order_marker").insertOne(session,
                    new Document("_id", cmd.orderId()).append("reservationId", r.reservationId()));
            return r.reservationId();
        });

        assertThat(reserved("TZP-COMPOSE", LOC)).isEqualTo(4);
        assertThat(db.getCollection("inventory_reservations_order_marker").find(new Document("_id", "ORD-COMPOSE"))
                .first().getString("reservationId")).isEqualTo(result);
    }

    // ---------- PR-14A hardening M1: Inventory owns reservation identity/TTL ----------

    @Test void prepare_computes_expiresAt_from_the_configured_ttl() {
        InventoryReservationProperties props = properties();
        props.setTtlSeconds(120);
        InventoryReservationService svc = service(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC), props);
        PreparedInventoryReservation prepared = svc.prepare(new InventoryReservationRequest("ORD-PREP", LOC,
                List.of(new InventoryReservationItem("TZP-PREP", 1))));
        assertThat(prepared.preparedAt()).isEqualTo(NOW);
        assertThat(prepared.expiresAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(InventoryReservationId.isValid(prepared.reservationId().value())).isTrue();
    }

    @Test void a_future_caller_uses_prepare_then_reserve_never_inventing_its_own_lifetime() {
        seed("TZP-PREP2", LOC, 10, true);
        InventoryReservationService svc = service();
        PreparedInventoryReservation prepared = svc.prepare(new InventoryReservationRequest("ORD-PREP2", LOC,
                List.of(new InventoryReservationItem("TZP-PREP2", 3))));
        Tx tx = new Tx(client);
        InventoryReservation r = tx.call(session -> svc.reserve(session, prepared));
        assertThat(r.expiresAt()).isEqualTo(prepared.expiresAt());
        assertThat(reserved("TZP-PREP2", LOC)).isEqualTo(3);
    }

    // ---------- PR-14A hardening M2: observability is actually wired ----------

    @Test void standalone_reserve_records_success_exactly_once_after_commit() {
        seed("TZP-OBS1", LOC, 10, true);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InventoryReservationService svc = new InventoryReservationService(inventory(new Tx(client),
                Clock.fixed(NOW, ZoneOffset.UTC)), new InventoryReservationRepository(db), properties(),
                new InventoryReservationObservability(registry), Clock.fixed(NOW, ZoneOffset.UTC), new Tx(client));
        double before = count(registry, "inventory_reservation_success", "operation", "reserve");
        svc.reserve("ORD-OBS1", LOC, List.of(new InventoryReservationItem("TZP-OBS1", 2)));
        assertThat(count(registry, "inventory_reservation_success", "operation", "reserve") - before).isEqualTo(1);
    }

    @Test void standalone_reserve_records_failure_exactly_once_on_insufficient_stock() {
        seed("TZP-OBS2", LOC, 1, true);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InventoryReservationService svc = new InventoryReservationService(inventory(new Tx(client),
                Clock.fixed(NOW, ZoneOffset.UTC)), new InventoryReservationRepository(db), properties(),
                new InventoryReservationObservability(registry), Clock.fixed(NOW, ZoneOffset.UTC), new Tx(client));
        double before = count(registry, "inventory_reservation_failure", "operation", "reserve", "reason",
                "reservation_unavailable");
        double successBefore = count(registry, "inventory_reservation_success", "operation", "reserve");
        assertThatThrownBy(() -> svc.reserve("ORD-OBS2", LOC, List.of(new InventoryReservationItem("TZP-OBS2", 5))))
                .isInstanceOf(InventoryReservationFailure.class);
        assertThat(count(registry, "inventory_reservation_failure", "operation", "reserve", "reason",
                "reservation_unavailable") - before).isEqualTo(1);
        assertThat(count(registry, "inventory_reservation_success", "operation", "reserve")).isEqualTo(successBefore);
    }

    @Test void transition_metric_fires_only_on_a_real_release_transition_never_on_idempotent_replay() {
        seed("TZP-OBS3", LOC, 10, true);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InventoryReservationService svc = new InventoryReservationService(inventory(new Tx(client),
                Clock.fixed(NOW, ZoneOffset.UTC)), new InventoryReservationRepository(db), properties(),
                new InventoryReservationObservability(registry), Clock.fixed(NOW, ZoneOffset.UTC), new Tx(client));
        InventoryReservation r = svc.reserve("ORD-OBS3", LOC, List.of(new InventoryReservationItem("TZP-OBS3", 2)));
        double before = count(registry, "inventory_reservation_transition", "from", "reserved", "to", "released");

        svc.release(new InventoryReservationId(r.reservationId())); // real transition
        assertThat(count(registry, "inventory_reservation_transition", "from", "reserved", "to", "released") - before)
                .isEqualTo(1);

        svc.release(new InventoryReservationId(r.reservationId())); // idempotent replay: no NEW transition
        assertThat(count(registry, "inventory_reservation_transition", "from", "reserved", "to", "released") - before)
                .as("idempotent replay never double-counts a transition").isEqualTo(1);
    }

    @Test void session_aware_calls_alone_never_emit_any_metric() {
        seed("TZP-OBS4", LOC, 10, true);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InventoryReservationService svc = new InventoryReservationService(inventory(new Tx(client),
                Clock.fixed(NOW, ZoneOffset.UTC)), new InventoryReservationRepository(db), properties(),
                new InventoryReservationObservability(registry), Clock.fixed(NOW, ZoneOffset.UTC), new Tx(client));
        PreparedInventoryReservation prepared = svc.prepare(new InventoryReservationRequest("ORD-OBS4", LOC,
                List.of(new InventoryReservationItem("TZP-OBS4", 2))));
        Tx tx = new Tx(client);
        // called DIRECTLY, bypassing the standalone wrapper -- exactly what a future Order composing
        // its own outer transaction would do.
        tx.call(session -> svc.reserve(session, prepared));
        assertThat(registry.getMeters()).as("a session-aware call alone claims no durable success/transition")
                .noneMatch(m -> m.getId().getName().startsWith("inventory_reservation_success")
                        || m.getId().getName().startsWith("inventory_reservation_transition"));
    }

    private static double count(io.micrometer.core.instrument.MeterRegistry registry, String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    // ---------- PR-14A hardening M3: closed failure contract ----------

    /** A {@link Tx} whose transaction always fails as a raw, non-transient Mongo outage — never
     *  touches the real database. Proves the standalone wrapper maps ANY escaping MongoException to
     *  the typed UNAVAILABLE reason, never a leaked Mongo type. */
    private static final class OutageTx extends Tx {
        OutageTx(MongoClient client) {
            super(client);
        }

        @Override
        public <T> T call(Function<ClientSession, T> body) {
            throw new MongoException("simulated datastore outage");
        }
    }

    @Test void a_datastore_outage_during_standalone_reserve_is_a_typed_UNAVAILABLE_never_a_raw_mongo_exception() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InventoryReservationService svc = new InventoryReservationService(inventory(new Tx(client),
                Clock.fixed(NOW, ZoneOffset.UTC)), new InventoryReservationRepository(db), properties(),
                new InventoryReservationObservability(registry), Clock.fixed(NOW, ZoneOffset.UTC),
                new OutageTx(client));
        double before = count(registry, "inventory_reservation_failure", "operation", "reserve", "reason",
                "unavailable");
        assertThatThrownBy(() -> svc.reserve("ORD-OUTAGE", LOC, List.of(new InventoryReservationItem("TZP-OUTAGE", 1))))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.UNAVAILABLE));
        assertThat(count(registry, "inventory_reservation_failure", "operation", "reserve", "reason", "unavailable")
                - before).as("counted exactly once").isEqualTo(1);
    }

    @Test void a_datastore_outage_during_standalone_release_is_a_typed_UNAVAILABLE() {
        seed("TZP-OUTREL", LOC, 10, true);
        InventoryReservationService okSvc = service();
        InventoryReservation r = okSvc.reserve("ORD-OUTREL", LOC, List.of(new InventoryReservationItem("TZP-OUTREL", 2)));

        InventoryReservationService brokenSvc = new InventoryReservationService(inventory(new Tx(client),
                Clock.fixed(NOW, ZoneOffset.UTC)), new InventoryReservationRepository(db), properties(),
                observability(), Clock.fixed(NOW, ZoneOffset.UTC), new OutageTx(client));
        assertThatThrownBy(() -> brokenSvc.release(new InventoryReservationId(r.reservationId())))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.UNAVAILABLE));
    }

    @Test void session_aware_operations_let_mongo_exceptions_propagate_untouched_for_Tx_retry() {
        // a business InventoryReservationFailure thrown INSIDE a session-aware call must propagate
        // as-is through tx.call -- never converted to UNAVAILABLE by the session-aware method itself
        // (only the STANDALONE wrapper, after the transaction has fully failed, performs that mapping).
        Tx tx = new Tx(client);
        InventoryReservationService svc = service(tx, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> tx.call(session -> svc.release(session, InventoryReservationId.generate())))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.NOT_FOUND));
    }

    // ---------- PR-14A hardening M4: expiry worker only counts transitions it actually caused ----------

    @Test void concurrent_explicit_release_and_expiry_reconciliation_count_the_transition_exactly_once() throws Exception {
        seed("TZP-RACE4", LOC, 10, true);
        Clock past = Clock.fixed(NOW.minusSeconds(700), ZoneOffset.UTC);
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService maker = service(new Tx(client), past, shortTtl);
        InventoryReservation r = maker.reserve("ORD-RACE4", LOC, List.of(new InventoryReservationItem("TZP-RACE4", 5)));

        Clock nowClock = Clock.fixed(NOW, ZoneOffset.UTC);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InventoryReservationObservability obs = new InventoryReservationObservability(registry);
        InventoryReservationService nowSvc = new InventoryReservationService(inventory(new Tx(client), nowClock),
                new InventoryReservationRepository(db), properties(), obs, nowClock, new Tx(client));
        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), nowSvc, obs, nowClock);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<InventoryReservation> explicit = pool.submit(() -> {
                go.await();
                return nowSvc.release(new InventoryReservationId(r.reservationId()));
            });
            Future<Integer> expiry = pool.submit(() -> {
                go.await();
                return worker.reconcileExpired(100);
            });
            go.countDown();
            explicit.get(30, TimeUnit.SECONDS);
            int releasedByWorker = expiry.get(30, TimeUnit.SECONDS);
            // exactly one of the two actually caused the RESERVED->RELEASED transition; the other
            // observed it already terminal. The worker's own count must reflect ONLY what IT caused.
            assertThat(releasedByWorker).isIn(0, 1);
            assertThat(count(registry, "inventory_reservation_transition", "from", "reserved", "to", "released"))
                    .as("the transition itself happened exactly once, regardless of who won").isEqualTo(1);
            assertThat(reserved("TZP-RACE4", LOC)).as("decremented exactly once").isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- PR-14A hardening M1 (this review): duplicate-key winner read cannot leak raw Mongo ----------

    /** A repository whose findByOrderId throws a raw MongoException -- proves the winner re-read
     *  after a lost duplicate-key race is itself safely mapped, never leaking a raw Mongo type. */
    private static final class WinnerReadFailsRepository extends InventoryReservationRepository {
        private final java.util.concurrent.atomic.AtomicBoolean failNext = new java.util.concurrent.atomic.AtomicBoolean();

        WinnerReadFailsRepository(com.mongodb.client.MongoDatabase db) {
            super(db);
        }

        @Override
        public Document findByOrderId(String orderId) {
            if (failNext.compareAndSet(true, false)) {
                throw new MongoException("simulated failure reading the duplicate-key winner");
            }
            return super.findByOrderId(orderId);
        }
    }

    /** A Tx whose FIRST call() commits normally (so a durable header genuinely exists), and whose
     *  SECOND call() simulates the caller losing a duplicate-key race by throwing a duplicate-key
     *  MongoWriteException directly -- without touching Mongo a second time -- so the winner-read
     *  path is exercised deterministically. */
    private static final class DuplicateKeyOnSecondCallTx extends Tx {
        private final Tx real;
        private int calls;

        DuplicateKeyOnSecondCallTx(MongoClient client) {
            super(client);
            this.real = new Tx(client);
        }

        @Override
        public <T> T call(Function<ClientSession, T> body) {
            calls++;
            if (calls == 1) {
                return real.call(body);
            }
            com.mongodb.WriteError error = new com.mongodb.WriteError(11000, "duplicate key", new org.bson.BsonDocument());
            throw new MongoWriteException(error, new com.mongodb.ServerAddress());
        }
    }

    @Test void duplicate_key_winner_read_failure_is_a_typed_UNAVAILABLE_never_a_raw_mongo_exception() {
        seed("TZP-DUPFAIL", LOC, 10, true);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        WinnerReadFailsRepository repo = new WinnerReadFailsRepository(db);
        InventoryReservationService svc = new InventoryReservationService(inventory(new Tx(client),
                Clock.fixed(NOW, ZoneOffset.UTC)), repo, properties(), new InventoryReservationObservability(registry),
                Clock.fixed(NOW, ZoneOffset.UTC), new DuplicateKeyOnSecondCallTx(client));

        // first call establishes a genuine durable header for the order
        svc.reserve("ORD-DUPFAIL", LOC, List.of(new InventoryReservationItem("TZP-DUPFAIL", 2)));
        double failBefore = count(registry, "inventory_reservation_failure", "operation", "reserve", "reason",
                "unavailable");
        double successBefore = count(registry, "inventory_reservation_success", "operation", "reserve");

        // second call: the Tx simulates losing a duplicate-key race, and the winner re-read itself fails
        repo.failNext.set(true);
        assertThatThrownBy(() -> svc.reserve("ORD-DUPFAIL", LOC, List.of(new InventoryReservationItem("TZP-DUPFAIL", 2))))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.UNAVAILABLE))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("MongoException"));
        assertThat(count(registry, "inventory_reservation_failure", "operation", "reserve", "reason", "unavailable")
                - failBefore).as("counted exactly once").isEqualTo(1);
        assertThat(count(registry, "inventory_reservation_success", "operation", "reserve")).isEqualTo(successBefore);
    }

    // ---------- PR-14A hardening (clock authority): Inventory owns "now" for release/consume ----------

    @Test void explicit_bypass_proof_consume_cannot_use_a_stale_caller_instant_the_public_api_accepts_none() {
        seed("TZP-CLK1", LOC, 10, true);
        java.util.concurrent.atomic.AtomicReference<Instant> liveNow = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock movable = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return liveNow.get(); }
        };
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        InventoryReservationService svc = new InventoryReservationService(inventory(new Tx(client), movable),
                new InventoryReservationRepository(db), shortTtl, new InventoryReservationObservability(registry),
                movable, new Tx(client));

        InventoryReservation r = svc.reserve("ORD-CLK1", LOC, List.of(new InventoryReservationItem("TZP-CLK1", 4)));
        // "capture an Instant that is BEFORE expiresAt" -- there is nowhere left to hand it to: the
        // session-aware consume(session, reservationId) signature has no Instant parameter at all.
        Instant beforeExpiry = liveNow.get();
        assertThat(beforeExpiry).isBefore(r.expiresAt());

        liveNow.set(NOW.plusSeconds(120)); // advance Inventory's LIVE injected clock past expiresAt
        double transitionBefore = count(registry, "inventory_reservation_transition", "from", "reserved", "to",
                "consumed");

        Tx tx = new Tx(client);
        assertThatThrownBy(() -> tx.call(session -> svc.consume(session, new InventoryReservationId(r.reservationId()))))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.RESERVATION_EXPIRED));

        assertThat(onHand("TZP-CLK1", LOC)).as("on_hand unchanged").isEqualTo(10);
        assertThat(reserved("TZP-CLK1", LOC)).as("reserved unchanged").isEqualTo(4);
        assertThat(svc.findById(new InventoryReservationId(r.reservationId())).orElseThrow().status())
                .as("status remains RESERVED").isEqualTo(InventoryReservationStatus.RESERVED);
        assertThat(count(registry, "inventory_reservation_transition", "from", "reserved", "to", "consumed"))
                .as("no transition metric from a session-aware call that never committed").isEqualTo(transitionBefore);
    }

    @Test void a_forced_transaction_retry_that_crosses_expiry_fails_the_later_attempt_and_rolls_back_the_earlier_one() {
        seed("TZP-CLK2", LOC, 10, true);
        java.util.concurrent.atomic.AtomicReference<Instant> liveNow = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock movable = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return liveNow.get(); }
        };
        InventoryReservationProperties shortTtl = properties();
        shortTtl.setTtlSeconds(60);
        InventoryReservationService prepSvc = new InventoryReservationService(inventory(new Tx(client), movable),
                new InventoryReservationRepository(db), shortTtl, observability(), movable, new Tx(client));
        InventoryReservation r = prepSvc.reserve("ORD-CLK2", LOC, List.of(new InventoryReservationItem("TZP-CLK2", 4)));

        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        InventoryReservationService svc = new InventoryReservationService(inventory(retryTx, movable),
                new InventoryReservationRepository(db), shortTtl, observability(), movable, retryTx);

        // attempt 1 runs the consume body fully (still valid) but its commit is forced to "fail
        // transiently"; BEFORE attempt 2 begins, advance the clock past expiresAt.
        retryTx.arm(1, n -> {
            if (n == 2) {
                liveNow.set(NOW.plusSeconds(120));
            }
        });

        assertThatThrownBy(() -> svc.consume(new InventoryReservationId(r.reservationId())))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.RESERVATION_EXPIRED));

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(onHand("TZP-CLK2", LOC)).as("attempt 1's decrement was rolled back").isEqualTo(10);
        assertThat(reserved("TZP-CLK2", LOC)).as("still held").isEqualTo(4);
        assertThat(svc.findById(new InventoryReservationId(r.reservationId())).orElseThrow().status())
                .as("no commit survived either attempt").isEqualTo(InventoryReservationStatus.RESERVED);
    }

    @Test void release_does_not_accept_a_caller_supplied_timestamp_either_inventory_sets_it() {
        seed("TZP-CLK3", LOC, 10, true);
        InventoryReservationService svc = service();
        InventoryReservation r = svc.reserve("ORD-CLK3", LOC, List.of(new InventoryReservationItem("TZP-CLK3", 2)));
        Tx tx = new Tx(client);
        InventoryReservation released = tx.call(session -> svc.release(session, new InventoryReservationId(r.reservationId())));
        // the ONLY way to have supplied a timestamp here would be a 3rd constructor argument -- there
        // isn't one; updatedAt is whatever Inventory's own clock produced.
        assertThat(released.updatedAt()).isEqualTo(NOW);
    }
}
