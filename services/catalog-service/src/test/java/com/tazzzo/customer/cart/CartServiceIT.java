package com.tazzzo.customer.cart;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.auth.session.CustomerIdentityAuthorityImpl;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-12C — {@link CartService} over a real Mongo + real {@link Tx}. No sleeps: latches for
 * concurrency, a mutable {@link Clock} for expiry, repository subclasses for failure/retry
 * injection. Customer identity is stubbed to "exists" here (synthetic ids); the real identity
 * behaviour is proven in the HTTP suites and in the dedicated tests below. SKU visibility is a
 * controlled set (the real catalog seam is exercised by the HTTP IT).
 */
@SpringBootTest(classes = {CatalogApplication.class, CartServiceIT.TestBeans.class})
class CartServiceIT extends AbstractMongoIT {

    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));
    static final Set<String> VISIBLE = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        CartLimitProperties smallLimits() {
            CartLimitProperties p = new CartLimitProperties();
            p.setMaxDistinctItems(3);
            p.setMaxQuantityPerItem(5);
            return p;
        }

        @Bean @Primary
        CommerceSkuBatchReader controlledVisibility(ProductCardBaseReader bases, PricingService pricing,
                                                    ProductCardRuntimeEnricher enricher) {
            return new CommerceSkuBatchReader(sku -> VISIBLE.contains(sku)
                    ? Optional.of(new CatalogCardFacts(sku, sku, "T " + sku, null, "V", 1L)) : Optional.empty(),
                    bases, pricing, enricher);
        }
    }

    @Autowired CartService service;
    @Autowired CartRepository cartRepo;
    @Autowired Clock clock;
    @Autowired Tx tx;
    @Autowired CartLimitProperties limits;
    @Autowired CartObservability observability;
    @Autowired CommerceSkuBatchReader commerce;
    @Autowired CustomerIdentityAuthorityImpl realIdentityAuthority;
    @Autowired MongoDatabase database;

    @BeforeEach
    void reset() {
        NOW.set(Instant.parse("2026-06-01T00:00:00Z"));
        VISIBLE.clear();
        for (int i = 1; i <= 9; i++) VISIBLE.add("TZP-" + i);
    }

    private static CustomerId cust(String s) {
        return new CustomerId("CUS_cartit" + s);
    }

    private static CartState.Line line(CartState s, String sku) {
        return s.lines().stream().filter(l -> l.skuId().equals(sku)).findFirst().orElseThrow();
    }

    private static void assertFailure(Runnable r, CartFailure.Reason reason) {
        assertThatThrownBy(r::run).isInstanceOf(CartFailure.class)
                .satisfies(e -> assertThat(((CartFailure) e).reason()).isEqualTo(reason));
    }

    // ---------- basics / versioning ----------

    @Test void first_put_with_cart_zero_creates_version_one_and_second_sku_advances_it() {
        CustomerId c = cust("0001");
        assertThat(service.get(c).version()).isZero();
        CartState v1 = service.setItem(c, "TZP-1", 2, 0);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.lines()).hasSize(1);
        CartState v2 = service.setItem(c, "TZP-2", 1, 1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(service.get(c).lines()).hasSize(2);
    }

    @Test void set_quantity_is_exact_not_incremental_and_keeps_added_at() {
        CustomerId c = cust("0002");
        CartState a = service.setItem(c, "TZP-1", 2, 0);
        NOW.set(NOW.get().plusSeconds(60));
        CartState b = service.setItem(c, "TZP-1", 4, 1);
        assertThat(line(b, "TZP-1").quantity()).isEqualTo(4);
        assertThat(line(b, "TZP-1").addedAt()).isEqualTo(line(a, "TZP-1").addedAt());
        assertThat(line(b, "TZP-1").updatedAt()).isAfter(line(a, "TZP-1").updatedAt());
    }

    @Test void quantity_bounds_are_enforced() {
        CustomerId c = cust("0003");
        assertFailure(() -> service.setItem(c, "TZP-1", 0, 0), CartFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> service.setItem(c, "TZP-1", -1, 0), CartFailure.Reason.INVALID_REQUEST);
        assertFailure(() -> service.setItem(c, "TZP-1", 6, 0), CartFailure.Reason.INVALID_REQUEST);
        assertThat(service.setItem(c, "TZP-1", 5, 0).version()).isEqualTo(1);
    }

    @Test void unknown_or_hidden_sku_cannot_be_added_and_nothing_is_persisted() {
        CustomerId c = cust("0004");
        assertFailure(() -> service.setItem(c, "TZP-999", 1, 0), CartFailure.Reason.NOT_FOUND);
        assertThat(cartRepo.findById(c.value())).isNull();
    }

    @Test void remove_line_and_absent_line_is_not_found() {
        CustomerId c = cust("0005");
        service.setItem(c, "TZP-1", 1, 0);
        service.setItem(c, "TZP-2", 1, 1);
        CartState removed = service.removeItem(c, "TZP-1", 2);
        assertThat(removed.version()).isEqualTo(3);
        assertThat(removed.lines()).extracting(CartState.Line::skuId).containsExactly("TZP-2");
        assertFailure(() -> service.removeItem(c, "TZP-1", 3), CartFailure.Reason.NOT_FOUND);
    }

    @Test void removing_the_last_item_keeps_an_empty_versioned_cart() {
        CustomerId c = cust("0006");
        service.setItem(c, "TZP-1", 1, 0);
        CartState empty = service.removeItem(c, "TZP-1", 1);
        assertThat(empty.lines()).isEmpty();
        assertThat(empty.version()).isEqualTo(2);
        assertThat(service.get(c).version()).as("no reset to 0").isEqualTo(2);
    }

    @Test void clear_empties_and_advances_version_and_clear_of_cart_zero_is_a_noop() {
        CustomerId c = cust("0007");
        CartState noop = service.clear(c, 0);
        assertThat(noop.version()).isZero();
        assertThat(cartRepo.findById(c.value())).as("no-op creates nothing").isNull();

        service.setItem(c, "TZP-1", 1, 0);
        service.setItem(c, "TZP-2", 1, 1);
        CartState cleared = service.clear(c, 2);
        assertThat(cleared.lines()).isEmpty();
        assertThat(cleared.version()).isEqualTo(3);
    }

    @Test void stale_version_is_rejected_and_state_is_unchanged() {
        CustomerId c = cust("0008");
        service.setItem(c, "TZP-1", 1, 0);
        assertFailure(() -> service.setItem(c, "TZP-2", 1, 0), CartFailure.Reason.PRECONDITION_FAILED);
        assertFailure(() -> service.removeItem(c, "TZP-1", 0), CartFailure.Reason.PRECONDITION_FAILED);
        assertFailure(() -> service.clear(c, 0), CartFailure.Reason.PRECONDITION_FAILED);
        CartState s = service.get(c);
        assertThat(s.version()).isEqualTo(1);
        assertThat(s.lines()).hasSize(1);
    }

    // ---------- limits ----------

    @Test void distinct_item_limit_is_enforced_but_updating_an_existing_sku_is_allowed() {
        CustomerId c = cust("0009");
        service.setItem(c, "TZP-1", 1, 0);
        service.setItem(c, "TZP-2", 1, 1);
        service.setItem(c, "TZP-3", 1, 2);
        assertFailure(() -> service.setItem(c, "TZP-4", 1, 3), CartFailure.Reason.CART_ITEM_LIMIT_REACHED);
        assertThat(service.setItem(c, "TZP-3", 5, 3).version()).isEqualTo(4);
    }

    // ---------- concurrency (latches, no sleeps) ----------

    private List<Object> race(int threads, java.util.function.IntFunction<Runnable> work) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Object> results = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try { go.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    try {
                        work.apply(idx).run();
                        results.add("ok");
                    } catch (CartFailure e) {
                        results.add(e);
                    }
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        return results;
    }

    private static long okCount(List<Object> r) {
        return r.stream().filter("ok"::equals).count();
    }

    private static long failCount(List<Object> r, CartFailure.Reason reason) {
        return r.stream().filter(x -> x instanceof CartFailure f && f.reason() == reason).count();
    }

    @Test void two_concurrent_mutations_at_the_same_version_yield_exactly_one_winner() throws Exception {
        CustomerId c = cust("0010");
        service.setItem(c, "TZP-1", 1, 0);
        List<Object> r = race(2, i -> () -> service.setItem(c, i == 0 ? "TZP-2" : "TZP-3", 1, 1));
        assertThat(okCount(r)).isEqualTo(1);
        assertThat(failCount(r, CartFailure.Reason.PRECONDITION_FAILED)).isEqualTo(1);
        CartState s = service.get(c);
        assertThat(s.version()).isEqualTo(2);
        assertThat(s.lines()).hasSize(2);
    }

    @Test void two_concurrent_first_creates_from_cart_zero_yield_exactly_one_winner() throws Exception {
        CustomerId c = cust("0011");
        List<Object> r = race(2, i -> () -> service.setItem(c, i == 0 ? "TZP-1" : "TZP-2", 1, 0));
        assertThat(okCount(r)).isEqualTo(1);
        assertThat(failCount(r, CartFailure.Reason.PRECONDITION_FAILED)).isEqualTo(1);
        assertThat(service.get(c).version()).isEqualTo(1);
    }

    @Test void concurrent_adds_at_the_same_version_near_the_item_limit_cannot_exceed_it() throws Exception {
        CustomerId c = cust("0012");
        service.setItem(c, "TZP-1", 1, 0);
        service.setItem(c, "TZP-2", 1, 1); // limit is 3: exactly one slot left at version 2
        List<Object> r = race(3, i -> () -> service.setItem(c, "TZP-" + (5 + i), 1, 2));
        assertThat(okCount(r)).isEqualTo(1);
        assertThat(service.get(c).lines()).hasSize(3);
    }

    // ---------- expiry (deterministic clock) ----------

    private void seedCart(CustomerId c, long version, Instant expiresAt, String... skus) {
        List<Document> items = new java.util.ArrayList<>();
        for (String s : skus) {
            items.add(new Document("skuId", s).append("quantity", 1)
                    .append("addedAt", Date.from(NOW.get())).append("updatedAt", Date.from(NOW.get())));
        }
        database.getCollection("customer_carts").insertOne(new Document("_id", c.value()).append("items", items)
                .append("version", version).append("createdAt", Date.from(NOW.get()))
                .append("updatedAt", Date.from(NOW.get())).append("expiresAt", Date.from(expiresAt)));
    }

    @Test void carts_younger_than_seven_days_are_preserved() {
        CustomerId c = cust("0013");
        service.setItem(c, "TZP-1", 1, 0);
        NOW.set(NOW.get().plus(Duration.ofHours(23)));
        assertThat(service.get(c).lines()).hasSize(1);
        NOW.set(Instant.parse("2026-06-01T00:00:00Z").plus(Duration.ofDays(6)).plus(Duration.ofHours(23)));
        CartState s = service.get(c);
        assertThat(s.lines()).as("1-7 days: preserved (revalidated at read time)").hasSize(1);
        assertThat(s.version()).isEqualTo(1);
    }

    @Test void a_cart_just_over_seven_days_expires_and_the_version_advances_never_resets() {
        CustomerId c = cust("0014");
        service.setItem(c, "TZP-1", 1, 0);
        service.setItem(c, "TZP-2", 1, 1); // version 2, expires t0+7d
        NOW.set(Instant.parse("2026-06-01T00:00:00Z").plus(Duration.ofDays(7)));
        CartState kept = service.get(c);
        assertThat(kept.lines()).as("exactly 7 days: still kept (revalidate band)").hasSize(2);
        assertThat(kept.version()).isEqualTo(2);
        NOW.set(Instant.parse("2026-06-01T00:00:00Z").plus(Duration.ofDays(7)).plusMillis(1));
        CartState expired = service.get(c);
        assertThat(expired.lines()).isEmpty();
        assertThat(expired.version()).as("advanced past 2, NOT reset to 0").isEqualTo(3);
        assertThat(expired.expiredCleared()).isTrue();
        assertThat(service.get(c).version()).isEqualTo(3);

        // A stale client still holding cart-2 (or the reset value cart-0) cannot write over it.
        assertFailure(() -> service.setItem(c, "TZP-1", 1, 2), CartFailure.Reason.PRECONDITION_FAILED);
        assertFailure(() -> service.setItem(c, "TZP-1", 1, 0), CartFailure.Reason.PRECONDITION_FAILED);
        assertThat(service.setItem(c, "TZP-1", 1, 3).version()).isEqualTo(4);
    }

    @Test void the_age_policy_bands_hold_at_every_boundary_and_a_kept_read_never_writes() {
        CustomerId c = cust("0040");
        Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
        service.setItem(c, "TZP-1", 1, 0);
        Document stored = cartRepo.findById(c.value());
        Object[][] bands = {
                {Duration.ofHours(23).plusMinutes(59), CartState.Freshness.FRESH},
                {Duration.ofHours(24), CartState.Freshness.REVALIDATE},
                {Duration.ofHours(24).plusMillis(1), CartState.Freshness.REVALIDATE},
                {Duration.ofDays(6).plusHours(23).plusMinutes(59), CartState.Freshness.REVALIDATE},
                {Duration.ofDays(7), CartState.Freshness.REVALIDATE}};
        for (Object[] b : bands) {
            NOW.set(t0.plus((Duration) b[0]));
            CartState s = service.get(c);
            assertThat(s.freshness()).as("age %s", b[0]).isEqualTo(b[1]);
            assertThat(s.lines()).as("age %s: kept", b[0]).hasSize(1);
            assertThat(s.version()).as("age %s: no version change", b[0]).isEqualTo(1);
            assertThat(cartRepo.findById(c.value())).as("age %s: the read wrote nothing", b[0]).isEqualTo(stored);
        }
        NOW.set(t0.plus(Duration.ofDays(7)).plusMillis(1));
        CartState expired = service.get(c);
        assertThat(expired.lines()).as("just over 7 days: expired").isEmpty();
        assertThat(expired.version()).isEqualTo(2);
        assertThat(expired.freshness()).isEqualTo(CartState.Freshness.FRESH);

        // a mutation resets the age: the cart is FRESH again
        NOW.set(t0.plus(Duration.ofDays(8)));
        assertThat(service.setItem(c, "TZP-2", 1, 2).freshness()).isEqualTo(CartState.Freshness.FRESH);
        NOW.set(t0.plus(Duration.ofDays(8)).plus(Duration.ofHours(23)));
        assertThat(service.get(c).freshness()).isEqualTo(CartState.Freshness.FRESH);
    }

    @Test void a_mutation_on_an_expired_cart_treats_it_as_empty_and_continues_the_version() {
        CustomerId c = cust("0015");
        seedCart(c, 7, NOW.get().minusSeconds(1), "TZP-1", "TZP-2");
        CartState s = service.setItem(c, "TZP-3", 1, 7);
        assertThat(s.version()).isEqualTo(8);
        assertThat(s.lines()).extracting(CartState.Line::skuId).containsExactly("TZP-3");
        assertThat(s.expiredCleared()).isTrue();
    }

    @Test void every_successful_mutation_refreshes_expiry_to_seven_days_from_now() {
        CustomerId c = cust("0016");
        service.setItem(c, "TZP-1", 1, 0);
        NOW.set(NOW.get().plus(Duration.ofDays(5)));
        CartState s = service.setItem(c, "TZP-2", 1, 1);
        assertThat(s.expiresAt()).isEqualTo(NOW.get().plus(Duration.ofDays(7)));
        NOW.set(NOW.get().plus(Duration.ofDays(6))); // 11 days after the FIRST write, 6 after the refresh
        assertThat(service.get(c).lines()).hasSize(2);
    }

    // ---------- identity integrity ----------

    private CartService withRealIdentity() {
        return new CartService(cartRepo, limits, clock, observability, new FixedObjectProvider<>(realIdentityAuthority),
                commerce, tx);
    }

    @Test void missing_customer_identity_is_unavailable_and_creates_no_cart() {
        CustomerId ghost = cust("0017"); // never in `customers`
        CartService real = withRealIdentity();
        assertFailure(() -> real.setItem(ghost, "TZP-1", 1, 0), CartFailure.Reason.UNAVAILABLE);
        assertFailure(() -> real.get(ghost), CartFailure.Reason.UNAVAILABLE);
        assertThat(cartRepo.findById(ghost.value())).isNull();
    }

    @Test void identity_authority_failure_is_unavailable_and_creates_no_cart() {
        CustomerId c = cust("0022");
        CustomerIdentityAuthority broken = new CustomerIdentityAuthority() {
            @Override public boolean exists(CustomerId id) { throw new IllegalStateException("authority down"); }
            @Override public boolean exists(ClientSession s, CustomerId id) { throw new IllegalStateException("authority down"); }
        };
        CartService svc = new CartService(cartRepo, limits, clock, observability, new FixedObjectProvider<>(broken),
                commerce, tx);
        assertFailure(() -> svc.setItem(c, "TZP-1", 1, 0), CartFailure.Reason.UNAVAILABLE);
        assertFailure(() -> svc.get(c), CartFailure.Reason.UNAVAILABLE);
        assertFailure(() -> svc.clear(c, 0), CartFailure.Reason.UNAVAILABLE);
        assertThat(cartRepo.findById(c.value())).isNull();
    }

    // ---------- Tx retry safety / no post-commit reads ----------

    @Test void transaction_retry_returns_the_successful_attempt_and_never_duplicates_the_item() {
        CustomerId c = cust("0018");
        AtomicInteger attempts = new AtomicInteger();
        CartRepository retryOnce = new CartRepository(database) {
            @Override
            public void insert(ClientSession session, Document cart) {
                if (attempts.incrementAndGet() == 1) {
                    MongoException t = new MongoException("simulated transient transaction conflict");
                    t.addLabel("TransientTransactionError");
                    throw t;
                }
                super.insert(session, cart);
            }
        };
        CartService svc = new CartService(retryOnce, limits, clock, observability,
                new FixedObjectProvider<>(new CustomerIdentityAuthority() {
                    @Override public boolean exists(CustomerId id) { return true; }
                    @Override public boolean exists(ClientSession s, CustomerId id) { return true; }
                }), commerce, tx);

        CartState s = svc.setItem(c, "TZP-1", 2, 0);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(s.version()).isEqualTo(1);
        assertThat(s.lines()).hasSize(1);
        Document persisted = cartRepo.findById(c.value());
        assertThat(persisted.getList("items", Document.class)).hasSize(1);
        assertThat(persisted.get("version", Number.class).longValue()).isEqualTo(1);
    }

    @Test void mutation_responses_need_no_post_commit_cart_read() {
        CustomerId c = cust("0019");
        CartRepository forbidsPostCommitRead = new CartRepository(database) {
            @Override
            public Document findById(String customerId) {
                throw new AssertionError("a mutation must not re-read the cart after commit");
            }
        };
        CartService svc = new CartService(forbidsPostCommitRead, limits, clock, observability,
                new FixedObjectProvider<>(new CustomerIdentityAuthority() {
                    @Override public boolean exists(CustomerId id) { return true; }
                    @Override public boolean exists(ClientSession s, CustomerId id) { return true; }
                }), commerce, tx);
        assertThat(svc.setItem(c, "TZP-1", 1, 0).version()).isEqualTo(1);
        assertThat(svc.setItem(c, "TZP-2", 1, 1).version()).isEqualTo(2);
        assertThat(svc.removeItem(c, "TZP-1", 2).version()).isEqualTo(3);
        assertThat(svc.clear(c, 3).version()).isEqualTo(4);
    }

    // ---------- clock discipline ----------

    @Test void timestamps_come_from_the_injected_clock() {
        CustomerId c = cust("0020");
        Instant t = Instant.parse("2026-07-01T10:00:00Z");
        NOW.set(t);
        service.setItem(c, "TZP-1", 1, 0);
        Document d = cartRepo.findById(c.value());
        assertThat(d.getDate("createdAt").toInstant()).isEqualTo(t);
        assertThat(d.getDate("updatedAt").toInstant()).isEqualTo(t);
        assertThat(d.getDate("expiresAt").toInstant()).isEqualTo(t.plus(Duration.ofDays(7)));
    }

    // ---------- PII-safe logging ----------

    @Test void a_repository_outage_never_logs_the_raw_exception_message() {
        CustomerId c = cust("0021");
        String fake = "pii@example.com CUS_sensitive Secret Name";
        CartRepository throwing = new CartRepository(database) {
            @Override
            public Document findById(ClientSession session, String customerId) {
                throw new RuntimeException(fake);
            }
        };
        CartService svc = new CartService(throwing, limits, clock, observability,
                new FixedObjectProvider<>(new CustomerIdentityAuthority() {
                    @Override public boolean exists(CustomerId id) { return true; }
                    @Override public boolean exists(ClientSession s, CustomerId id) { return true; }
                }), commerce, tx);
        Logger logger = (Logger) LoggerFactory.getLogger(CartService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertFailure(() -> svc.setItem(c, "TZP-1", 1, 0), CartFailure.Reason.UNAVAILABLE);
            StringBuilder sb = new StringBuilder();
            for (ILoggingEvent e : appender.list) {
                sb.append(e.getFormattedMessage());
                assertThat(e.getThrowableProxy()).isNull();
            }
            assertThat(sb.toString()).doesNotContain("pii@example.com").doesNotContain("CUS_sensitive")
                    .doesNotContain("Secret Name").contains("RuntimeException");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
