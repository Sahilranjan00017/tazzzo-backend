package com.tazzzo.customer.cart;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.CatalogCardFacts;
import com.tazzzo.commerce.read.CommerceSkuBatchReader;
import com.tazzzo.commerce.read.ProductCardBaseReader;
import com.tazzzo.commerce.read.ProductCardRuntimeEnricher;
import com.tazzzo.pricing.PricingService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-15A-0 — the cart purchase-finalization seam over a real Mongo + real {@link Tx}. The seam is
 * session-aware, so every scenario runs it INSIDE {@code tx.call}, exactly the way a future
 * {@code customer.order} placement transaction will. A scratch collection stands in for that
 * caller's own writes (the "Order insert"), so commit/rollback/exactly-once behaviour is asserted on
 * real committed state. No sleeps: latches for concurrency, a mutable {@link Clock}, repository
 * subclasses for transient-error and datastore-failure injection.
 */
@SpringBootTest(classes = {CatalogApplication.class, CartPurchaseServiceIT.TestBeans.class})
class CartPurchaseServiceIT extends AbstractMongoIT {

    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));
    static final Set<String> VISIBLE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final String SCRATCH = "pr15a0_scratch_orders";

    @TestConfiguration
    static class TestBeans {
        @Bean @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }

        @Bean @Primary
        CustomerIdentityAuthority alwaysExists() {
            return new CustomerIdentityAuthority() {
                @Override public boolean exists(CustomerId customerId) { return true; }
                @Override public boolean exists(ClientSession session, CustomerId customerId) { return true; }
            };
        }

        @Bean @Primary
        CommerceSkuBatchReader controlledVisibility(ProductCardBaseReader bases, PricingService pricing,
                                                    ProductCardRuntimeEnricher enricher) {
            return new CommerceSkuBatchReader(sku -> VISIBLE.contains(sku)
                    ? Optional.of(new CatalogCardFacts(sku, sku, "T " + sku, null, "V", 1L)) : Optional.empty(),
                    bases, pricing, enricher);
        }
    }

    @Autowired CartService cartService;
    @Autowired CartRepository cartRepo;
    @Autowired CartPurchaseService purchase;
    @Autowired CartPurchasePort port;
    @Autowired Clock clock;
    @Autowired Tx tx;
    @Autowired MongoDatabase database;

    @BeforeEach
    void reset() {
        NOW.set(Instant.parse("2026-06-01T00:00:00Z"));
        VISIBLE.clear();
        for (int i = 1; i <= 9; i++) VISIBLE.add("TZP-" + i);
        database.getCollection(SCRATCH).drop();
    }

    private static CustomerId cust(String s) {
        return new CustomerId("CUS_purchit" + s);
    }

    /** A cart at exactly {@code version} (one item added per version step). */
    private CartState cartAtVersion(CustomerId c, int version) {
        CartState s = CartState.empty(0);
        for (int v = 0; v < version; v++) {
            s = cartService.setItem(c, "TZP-" + (v % 9 + 1), 1 + (v / 9), v);
        }
        return s;
    }

    private Document cartDoc(CustomerId c) {
        return cartRepo.findById(c.value());
    }

    private static long marker(Document d) {
        Number n = d.get("purchasedThroughVersion", Number.class);
        return n == null ? 0L : n.longValue();
    }

    private boolean guard(CustomerId c, long source) {
        return tx.call(session -> port.isSourceVersionPurchased(session, c, source));
    }

    private CartPurchaseOutcome finalizeIn(CustomerId c, long source) {
        return tx.call(session -> port.finalizePurchase(session, c, source));
    }

    /** Mimics a fresh placement: guard, then the caller's own write, then finalize — one transaction. */
    private boolean placeFresh(CustomerId c, long source, String quoteTag) {
        return tx.call(session -> {
            if (port.isSourceVersionPurchased(session, c, source)) {
                return false;
            }
            database.getCollection(SCRATCH).insertOne(session, new Document("quote", quoteTag).append("c", c.value()));
            port.finalizePurchase(session, c, source);
            return true;
        });
    }

    private long scratchCount(CustomerId c) {
        return database.getCollection(SCRATCH).countDocuments(new Document("c", c.value()));
    }

    // ---------- guard ----------

    @Test void guard_is_false_for_a_missing_cart_and_for_a_never_purchased_cart() {
        assertThat(guard(cust("0001"), 1)).isFalse();
        CustomerId c = cust("0002");
        cartAtVersion(c, 2);
        assertThat(guard(c, 1)).isFalse();
        assertThat(guard(c, 2)).isFalse();
        assertThat(cartDoc(c).containsKey("purchasedThroughVersion")).isFalse(); // read-only: writes nothing
    }

    @Test void guard_treats_marker_as_covering_that_version_and_every_older_one() {
        CustomerId c = cust("0003");
        cartAtVersion(c, 3);
        finalizeIn(c, 3);
        assertThat(guard(c, 3)).isTrue();
        assertThat(guard(c, 2)).isTrue();
        assertThat(guard(c, 1)).isTrue();
        assertThat(guard(c, 4)).isFalse();
    }

    // ---------- clear-if-version ----------

    @Test void unchanged_source_cart_is_cleared_version_advances_marker_set_document_kept() {
        CustomerId c = cust("0004");
        cartAtVersion(c, 2);
        NOW.set(NOW.get().plusSeconds(90));

        CartPurchaseOutcome outcome = finalizeIn(c, 2);

        assertThat(outcome).isEqualTo(CartPurchaseOutcome.CLEARED);
        Document d = cartDoc(c);
        assertThat(d).isNotNull(); // never deleted
        assertThat(d.getList("items", Document.class)).isEmpty();
        assertThat(d.get("version", Number.class).longValue()).isEqualTo(3); // advanced, not reset
        assertThat(marker(d)).isEqualTo(2);
        assertThat(d.getDate("updatedAt").toInstant()).isEqualTo(NOW.get());
        assertThat(d.getDate("expiresAt").toInstant()).isEqualTo(NOW.get().plus(Duration.ofDays(7)));
        // the customer's next edit continues from the advanced version, exactly like after clear()
        CartState next = cartService.setItem(c, "TZP-1", 1, 3);
        assertThat(next.version()).isEqualTo(4);
        assertThat(marker(cartDoc(c))).isEqualTo(2); // ordinary cart writes preserve the marker
    }

    @Test void newer_edited_cart_is_not_cleared_only_the_marker_advances() {
        CustomerId c = cust("0005");
        cartAtVersion(c, 1);            // quote taken from v1
        CartState edited = cartService.setItem(c, "TZP-9", 3, 1); // customer edits -> v2
        assertThat(edited.version()).isEqualTo(2);
        Document before = cartDoc(c);
        NOW.set(NOW.get().plusSeconds(90));

        CartPurchaseOutcome outcome = finalizeIn(c, 1);

        assertThat(outcome).isEqualTo(CartPurchaseOutcome.NEWER_CART_PRESERVED);
        Document after = cartDoc(c);
        assertThat(after.getList("items", Document.class)).isEqualTo(before.getList("items", Document.class));
        assertThat(after.get("version", Number.class).longValue()).isEqualTo(2);       // unchanged
        assertThat(after.getDate("updatedAt")).isEqualTo(before.getDate("updatedAt"));   // untouched
        assertThat(after.getDate("expiresAt")).isEqualTo(before.getDate("expiresAt"));
        assertThat(marker(after)).isEqualTo(1);
        // the customer's own client, holding v2, is not disturbed
        assertThat(cartService.setItem(c, "TZP-8", 1, 2).version()).isEqualTo(3);
    }

    @Test void a_newer_cart_version_remains_eligible_after_an_older_version_was_purchased() {
        CustomerId c = cust("0006");
        cartAtVersion(c, 1);
        cartService.setItem(c, "TZP-9", 3, 1); // v2
        finalizeIn(c, 1);                      // v1 purchased, v2 preserved
        assertThat(guard(c, 2)).isFalse();     // a quote from v2 may still place
        assertThat(finalizeIn(c, 2)).isEqualTo(CartPurchaseOutcome.CLEARED);
        assertThat(marker(cartDoc(c))).isEqualTo(2);
    }

    @Test void a_stale_older_quote_is_covered_once_a_newer_version_was_purchased() {
        CustomerId c = cust("0007");
        cartAtVersion(c, 2);
        finalizeIn(c, 2);                      // v2 purchased
        assertThat(guard(c, 1)).isTrue();      // an unplaced v1 quote is now stale
    }

    @Test void marker_is_monotonic_and_never_lowered() {
        CustomerId c = cust("0008");
        cartAtVersion(c, 3);
        finalizeIn(c, 3);
        assertThat(marker(cartDoc(c))).isEqualTo(3);
        assertThat(finalizeIn(c, 1)).isEqualTo(CartPurchaseOutcome.NEWER_CART_PRESERVED); // live v4 > 1
        assertThat(marker(cartDoc(c))).isEqualTo(3);
        assertThat(finalizeIn(c, 2)).isEqualTo(CartPurchaseOutcome.NEWER_CART_PRESERVED);
        assertThat(marker(cartDoc(c))).isEqualTo(3);
    }

    @Test void an_expired_cart_at_the_source_version_is_still_cleared_never_deleted() {
        CustomerId c = cust("0009");
        cartAtVersion(c, 1);
        NOW.set(NOW.get().plus(Duration.ofDays(8))); // cart past its 7-day retention
        assertThat(finalizeIn(c, 1)).isEqualTo(CartPurchaseOutcome.CLEARED);
        Document d = cartDoc(c);
        assertThat(d.get("version", Number.class).longValue()).isEqualTo(2);
        assertThat(marker(d)).isEqualTo(1);
    }

    @Test void marker_survives_expiry_housekeeping_and_ordinary_cart_clears() {
        CustomerId c = cust("0010");
        cartAtVersion(c, 1);
        finalizeIn(c, 1);                                   // v2, marker 1
        cartService.setItem(c, "TZP-2", 1, 2);              // v3
        NOW.set(NOW.get().plus(Duration.ofDays(8)));
        assertThat(cartService.get(c).lines()).isEmpty();   // GET housekeeping clears the expired cart -> v4
        assertThat(marker(cartDoc(c))).isEqualTo(1);
        cartService.clear(c, 4);                            // ordinary clear -> v5
        assertThat(marker(cartDoc(c))).isEqualTo(1);
    }

    // ---------- integrity: fail loud, write nothing ----------

    @Test void missing_cart_document_fails_loud_and_creates_nothing() {
        CustomerId c = cust("0011");
        assertThatThrownBy(() -> finalizeIn(c, 1)).isInstanceOf(CartPurchaseIntegrityException.class);
        assertThat(cartDoc(c)).isNull();
    }

    @Test void live_version_below_the_source_version_fails_loud_and_writes_nothing() {
        CustomerId c = cust("0012");
        cartAtVersion(c, 1);
        Document before = cartDoc(c);
        assertThatThrownBy(() -> finalizeIn(c, 5)).isInstanceOf(CartPurchaseIntegrityException.class);
        assertThat(cartDoc(c)).isEqualTo(before);
    }

    @Test void corrupt_marker_fails_loud_on_guard_and_finalize_and_writes_nothing() {
        CustomerId c = cust("0013");
        cartAtVersion(c, 1);
        database.getCollection("customer_carts").updateOne(new Document("_id", c.value()),
                new Document("$set", new Document("purchasedThroughVersion", "oops")));
        Document before = cartDoc(c);
        assertThatThrownBy(() -> guard(c, 1)).isInstanceOf(CartPurchaseIntegrityException.class);
        assertThatThrownBy(() -> finalizeIn(c, 1)).isInstanceOf(CartPurchaseIntegrityException.class);
        assertThat(cartDoc(c)).isEqualTo(before);
        database.getCollection("customer_carts").updateOne(new Document("_id", c.value()),
                new Document("$set", new Document("purchasedThroughVersion", -1L)));
        assertThatThrownBy(() -> guard(c, 1)).isInstanceOf(CartPurchaseIntegrityException.class);
    }

    @Test void a_source_version_below_one_is_rejected_before_any_read() {
        CustomerId c = cust("0014");
        assertThatThrownBy(() -> guard(c, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> finalizeIn(c, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- atomicity with the caller's transaction ----------

    @Test void caller_rollback_after_a_clear_leaves_the_cart_and_marker_untouched() {
        CustomerId c = cust("0015");
        cartAtVersion(c, 2);
        Document before = cartDoc(c);
        assertThatThrownBy(() -> tx.call(session -> {
            database.getCollection(SCRATCH).insertOne(session, new Document("c", c.value()));
            assertThat(port.finalizePurchase(session, c, 2)).isEqualTo(CartPurchaseOutcome.CLEARED);
            throw new IllegalStateException("caller aborts after finalization");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(cartDoc(c)).isEqualTo(before);
        assertThat(marker(cartDoc(c))).isZero(); // purchasedThroughVersion did not advance
        assertThat(scratchCount(c)).isZero();
    }

    @Test void caller_rollback_after_a_newer_cart_preserve_leaves_the_marker_unchanged() {
        CustomerId c = cust("0016");
        cartAtVersion(c, 1);
        cartService.setItem(c, "TZP-9", 1, 1);
        Document before = cartDoc(c);
        assertThatThrownBy(() -> tx.call(session -> {
            assertThat(port.finalizePurchase(session, c, 1)).isEqualTo(CartPurchaseOutcome.NEWER_CART_PRESERVED);
            throw new IllegalStateException("caller aborts");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(cartDoc(c)).isEqualTo(before);
    }

    @Test void datastore_failure_while_clearing_aborts_the_caller_transaction_entirely() {
        CustomerId c = cust("0017");
        cartAtVersion(c, 2);
        Document before = cartDoc(c);
        CartRepository failing = new CartRepository(database) {
            @Override
            public boolean clearPurchasedIfVersion(ClientSession s, String id, long v, Instant n, Instant e) {
                throw new MongoException("simulated datastore failure");
            }
        };
        CartPurchaseService svc = new CartPurchaseService(failing, clock);
        assertThatThrownBy(() -> tx.call(session -> {
            database.getCollection(SCRATCH).insertOne(session, new Document("c", c.value()));
            return svc.finalizePurchase(session, c, 2);
        })).isInstanceOf(MongoException.class);
        assertThat(cartDoc(c)).isEqualTo(before);
        assertThat(scratchCount(c)).isZero(); // the caller's own write rolled back with it
    }

    // ---------- driver retry ----------

    @Test void forced_transaction_retry_clears_once_and_advances_the_version_once() {
        CustomerId c = cust("0018");
        cartAtVersion(c, 2);
        AtomicInteger attempts = new AtomicInteger();
        CartRepository retryOnce = new CartRepository(database) {
            @Override
            public boolean clearPurchasedIfVersion(ClientSession s, String id, long v, Instant n, Instant e) {
                if (attempts.incrementAndGet() == 1) {
                    super.clearPurchasedIfVersion(s, id, v, n, e); // attempt 1 really writes, then aborts
                    MongoException t = new MongoException("simulated transient transaction conflict");
                    t.addLabel("TransientTransactionError");
                    throw t;
                }
                return super.clearPurchasedIfVersion(s, id, v, n, e);
            }
        };
        CartPurchaseService svc = new CartPurchaseService(retryOnce, clock);

        CartPurchaseOutcome outcome = tx.call(session -> {
            database.getCollection(SCRATCH).insertOne(session, new Document("c", c.value()));
            return svc.finalizePurchase(session, c, 2);
        });

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(outcome).isEqualTo(CartPurchaseOutcome.CLEARED);
        Document d = cartDoc(c);
        assertThat(d.get("version", Number.class).longValue()).isEqualTo(3); // once, never 4
        assertThat(marker(d)).isEqualTo(2);
        assertThat(scratchCount(c)).isEqualTo(1);
    }

    @Test void forced_retry_on_the_newer_cart_branch_keeps_the_marker_idempotent() {
        CustomerId c = cust("0019");
        cartAtVersion(c, 1);
        cartService.setItem(c, "TZP-9", 1, 1); // v2
        AtomicInteger attempts = new AtomicInteger();
        CartRepository retryOnce = new CartRepository(database) {
            @Override
            public boolean markPurchasedThrough(ClientSession s, String id, long v) {
                boolean applied = super.markPurchasedThrough(s, id, v);
                if (attempts.incrementAndGet() == 1) {
                    MongoException t = new MongoException("simulated transient transaction conflict");
                    t.addLabel("TransientTransactionError");
                    throw t;
                }
                return applied;
            }
        };
        CartPurchaseService svc = new CartPurchaseService(retryOnce, clock);
        CartPurchaseOutcome outcome = tx.call(session -> svc.finalizePurchase(session, c, 1));
        assertThat(outcome).isEqualTo(CartPurchaseOutcome.NEWER_CART_PRESERVED);
        assertThat(attempts.get()).isEqualTo(2);
        Document d = cartDoc(c);
        assertThat(marker(d)).isEqualTo(1);
        assertThat(d.get("version", Number.class).longValue()).isEqualTo(2);
    }

    // ---------- the second-quote guard, as a placement transaction will use it ----------

    @Test void two_distinct_quotes_from_the_same_cart_version_only_the_first_places() {
        CustomerId c = cust("0020");
        cartAtVersion(c, 3);
        assertThat(placeFresh(c, 3, "quoteA")).isTrue();
        assertThat(placeFresh(c, 3, "quoteB")).isFalse(); // fresh placement rejected by the guard
        assertThat(scratchCount(c)).isEqualTo(1);
        assertThat(marker(cartDoc(c))).isEqualTo(3);
    }

    @Test void a_quote_from_v_older_than_the_purchased_version_is_rejected_v_newer_still_places() {
        CustomerId c = cust("0021");
        cartAtVersion(c, 1);
        cartService.setItem(c, "TZP-9", 1, 1); // v2
        assertThat(placeFresh(c, 1, "v1-quote")).isTrue();  // v1 purchased; v2 preserved
        assertThat(placeFresh(c, 1, "v1-again")).isFalse(); // second fresh v1 quote
        assertThat(placeFresh(c, 2, "v2-quote")).isTrue();  // newer v2 quote still eligible
        assertThat(placeFresh(c, 1, "v1-stale")).isFalse(); // now 1 <= 2
        assertThat(scratchCount(c)).isEqualTo(2);
    }

    @Test void a_rolled_back_placement_does_not_advance_the_marker_so_a_retry_can_still_place() {
        CustomerId c = cust("0022");
        cartAtVersion(c, 2);
        assertThatThrownBy(() -> tx.call(session -> {
            assertThat(port.isSourceVersionPurchased(session, c, 2)).isFalse();
            port.finalizePurchase(session, c, 2);
            throw new IllegalStateException("validation failed after finalization");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(marker(cartDoc(c))).isZero();
        assertThat(placeFresh(c, 2, "retry")).isTrue();
        assertThat(marker(cartDoc(c))).isEqualTo(2);
    }

    @Test void concurrent_placements_of_different_quotes_from_one_cart_version_exactly_one_wins()
            throws Exception {
        for (int round = 0; round < 5; round++) {
            CustomerId c = cust("003" + round);
            cartAtVersion(c, 2);
            int contenders = 4;
            CountDownLatch bothPassedGuard = new CountDownLatch(contenders);
            ExecutorService pool = Executors.newFixedThreadPool(contenders);
            try {
                java.util.List<Future<Boolean>> results = new java.util.ArrayList<>();
                for (int i = 0; i < contenders; i++) {
                    String quote = "q" + round + "-" + i;
                    AtomicInteger attempts = new AtomicInteger();
                    results.add(pool.submit(() -> tx.call(session -> {
                        boolean purchased = port.isSourceVersionPurchased(session, c, 2);
                        if (attempts.incrementAndGet() == 1) {
                            // first attempt only: every contender has read "not purchased" before any
                            // of them finalizes, so the race is real and deterministic, not timing luck.
                            bothPassedGuard.countDown();
                            try {
                                if (!bothPassedGuard.await(30, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("contenders did not rendezvous");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        }
                        if (purchased) {
                            return false;
                        }
                        database.getCollection(SCRATCH).insertOne(session,
                                new Document("quote", quote).append("c", c.value()));
                        port.finalizePurchase(session, c, 2);
                        return true;
                    })));
                }
                int winners = 0;
                for (Future<Boolean> f : results) {
                    if (f.get(60, TimeUnit.SECONDS)) winners++;
                }
                assertThat(winners).as("round %d", round).isEqualTo(1);
                assertThat(scratchCount(c)).isEqualTo(1);       // exactly one fresh "Order"
                Document d = cartDoc(c);
                assertThat(marker(d)).isEqualTo(2);
                assertThat(d.get("version", Number.class).longValue()).isEqualTo(3); // cleared exactly once
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ---------- the marker never leaks ----------

    @Test void the_marker_is_not_part_of_the_customer_facing_cart_contract() {
        assertThat(java.util.Arrays.stream(CartState.class.getRecordComponents()).map(rc -> rc.getName()))
                .doesNotContain("purchasedThroughVersion");
        assertThat(java.util.Arrays.stream(CartResponseDto.class.getRecordComponents()).map(rc -> rc.getName()))
                .doesNotContain("purchasedThroughVersion");
    }
}
