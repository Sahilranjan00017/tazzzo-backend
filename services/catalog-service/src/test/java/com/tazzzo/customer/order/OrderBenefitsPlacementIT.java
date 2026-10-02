package com.tazzzo.customer.order;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.benefits.BenefitEvaluation;
import com.tazzzo.benefits.BenefitRule;
import com.tazzzo.benefits.BenefitsFailure;
import com.tazzzo.benefits.BenefitsTransactionalEvaluator;
import com.tazzzo.benefits.ConfigBackedBenefitRuleSource;
import com.tazzzo.benefits.DiscountBps;
import com.tazzzo.benefits.TransactionalBenefitsEvaluationPort;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.money.Money;
import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.address.AddressRepository;
import com.tazzzo.customer.cart.CartPurchasePort;
import com.tazzzo.customer.cart.CartPurchaseService;
import com.tazzzo.customer.cart.CartRepository;
import com.tazzzo.customer.checkout.CheckoutQuote;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.customer.checkout.CheckoutQuoteRepository;
import com.tazzzo.inventory.InventoryReservationObservability;
import com.tazzzo.inventory.InventoryReservationPort;
import com.tazzzo.inventory.InventoryReservationProperties;
import com.tazzzo.inventory.InventoryReservationRepository;
import com.tazzzo.inventory.InventoryReservationService;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.membership.Membership;
import com.tazzzo.membership.MembershipService;
import com.tazzzo.membership.MembershipTerminationService;
import com.tazzzo.membership.TransactionalMembershipEntitlementPort;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.TransactionalPriceReadPort;
import com.tazzzo.serviceability.ServiceabilityService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-18A-1 — the AUTHORITATIVE Benefits evaluation inside the Order placement transaction, against real Mongo, the real
 * Membership beans and the real {@link BenefitsTransactionalEvaluator}. Every scenario asserts COMMITTED state. Benefits
 * rules here are explicit TEST FIXTURES (production configures none).
 */
@SpringBootTest(classes = CatalogApplication.class)
class OrderBenefitsPlacementIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final String PIN = "560001";
    private static final String LOC = "FL-BEN-1";
    private static final String SKU = "TZP-BEN1";
    private static final String PLAN = "TAZZZO_PLUS_MONTHLY";
    private static final long SUBTOTAL = 10_000; // 2 x 5000

    @Autowired MembershipService memberships;
    @Autowired MembershipTerminationService termination;
    @Autowired TransactionalMembershipEntitlementPort transactionalMembership;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void reset() {
        for (String c : List.of("orders", "checkout_quotes", "customer_addresses", "inventory_reservations",
                "inventory", "price_current", "service_areas", "products", "customer_carts")) {
            db.getCollection(c).deleteMany(new Document());
        }
        registry = new SimpleMeterRegistry();
    }

    // ---------- wiring ----------

    private static final ObjectProvider<CustomerIdentityAuthority> ALWAYS_EXISTS = new ObjectProvider<>() {
        @Override public CustomerIdentityAuthority getObject() {
            return new CustomerIdentityAuthority() {
                @Override public boolean exists(CustomerId customerId) { return true; }
                @Override public boolean exists(ClientSession session, CustomerId customerId) { return true; }
            };
        }
        @Override public CustomerIdentityAuthority getObject(Object... args) { return getObject(); }
        @Override public CustomerIdentityAuthority getIfAvailable() { return getObject(); }
        @Override public CustomerIdentityAuthority getIfUnique() { return getObject(); }
    };

    private InventoryReservationService reservationService(Tx tx, Clock clock) {
        InventoryReservationProperties p = new InventoryReservationProperties();
        p.setTtlSeconds(600);
        p.setExpiryBatchSize(100);
        return new InventoryReservationService(new InventoryService(tx, new WritePath(db), clock),
                new InventoryReservationRepository(db), p,
                new InventoryReservationObservability(new SimpleMeterRegistry()), clock, tx);
    }

    private OrderService service(Tx tx, TransactionalBenefitsEvaluationPort benefits) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return service(tx, new OrderRepository(db), new PricingService(tx, new WritePath(db), clock),
                reservationService(tx, clock), new CartPurchaseService(new CartRepository(db), clock), benefits);
    }

    /** The full builder: every collaborator the Order composes is injectable so a test can OBSERVE it. */
    private OrderService service(Tx tx, OrderRepository orders, TransactionalPriceReadPort pricing,
                                 InventoryReservationPort reservations, CartPurchasePort cart,
                                 TransactionalBenefitsEvaluationPort benefits) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new OrderService(orders, new CheckoutQuoteRepository(db), new AddressRepository(db),
                new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock), pricing,
                new com.tazzzo.commerce.read.CatalogCardReader(db), benefits, reservations, cart, clock,
                ALWAYS_EXISTS, tx, new OrderObservability(registry));
    }

    private OrderService service(TransactionalBenefitsEvaluationPort benefits) {
        return service(new Tx(client), benefits);
    }

    private TransactionalBenefitsEvaluationPort realBenefits(BenefitRule... rules) {
        return new BenefitsTransactionalEvaluator(transactionalMembership,
                new ConfigBackedBenefitRuleSource(List.of(rules)));
    }

    private static BenefitRule rule(long minimumPaise, int bps) {
        return new BenefitRule(PLAN, 1, Money.ofInrPaise(minimumPaise), new DiscountBps(bps));
    }

    private static final TransactionalBenefitsEvaluationPort MUST_NOT_BE_CALLED = (s, c, subtotal) -> {
        throw new AssertionError("Benefits must NOT be evaluated here");
    };

    private Membership grant(CustomerId customer) {
        return memberships.grant(customer, PLAN, 1, "REF-" + UUID.randomUUID());
    }

    // ---------- seeding (same shapes as OrderPlaceCodIT) ----------

    private void seed(String customerId, String addressId, long cartVersion) {
        db.getCollection("customer_addresses").insertOne(new Document("_id", addressId)
                .append("customerId", customerId).append("label", "HOME").append("recipientName", "Test Recipient")
                .append("recipientPhone", "9999999999").append("addressLine1", "123 Test Street")
                .append("addressLine2", "Near Landmark").append("landmark", "Landmark")
                .append("city", "Bengaluru").append("state", "Karnataka").append("postalCode", PIN)
                .append("latitude", 12.9716).append("longitude", 77.5946).append("version", 1L)
                .append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW)));
        db.getCollection("service_areas").insertOne(new Document("pincode", PIN)
                .append("service_area_id", "SA-1").append("active", true)
                .append("routes", List.of(new Document("fulfillment_location_id", LOC)
                        .append("priority", 1).append("active", true)))
                .append("version", 1L).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
        db.getCollection("price_current").insertOne(new Document("sku_id", SKU)
                .append("currency", "INR").append("selling_price_paise", 5000L).append("mrp_paise", 6000L)
                .append("version", 1L).append("active", true).append("effective_from", null)
                .append("effective_to", null).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
        db.getCollection("products").insertOne(new Document("_id", SKU).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", SKU))
                .append("brand_code", "BR-1").append("title", "Benefit Widget")
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", "V-1").append("release_id", "R1")
                        .append("status", "confirmed"))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date()));
        db.getCollection("inventory").insertOne(new Document("sku_id", SKU)
                .append("fulfillment_location_id", LOC).append("on_hand", 10L).append("reserved", 0L)
                .append("low_stock_threshold", 1L).append("max_purchasable", 100L).append("version", 1L)
                .append("active", true).append("source", "seed").append("created_at", new Date())
                .append("updated_at", new Date()));
        db.getCollection("customer_carts").insertOne(new Document("_id", customerId)
                .append("items", List.of(new Document("skuId", SKU).append("quantity", 2)
                        .append("addedAt", Date.from(NOW)).append("updatedAt", Date.from(NOW))))
                .append("version", cartVersion).append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW))
                .append("expiresAt", Date.from(NOW.plusSeconds(7 * 86400))));
    }

    private record Fixture(CustomerId customerId, String addressId, String quoteId, long cartVersion) { }

    private Fixture fixture() {
        CustomerId customerId = CustomerId.generate();
        String addressId = AddressId.generate().value();
        seed(customerId.value(), addressId, 1);
        String quoteId = CheckoutQuoteId.generate().value();
        CheckoutQuote q = new CheckoutQuote(quoteId, 1, addressId, 1L,
                List.of(new CheckoutQuote.Line(SKU, 2, 5000, SUBTOTAL)), 2, SUBTOTAL, "INR", NOW,
                NOW.plusSeconds(600));
        new Tx(client).run(session -> new CheckoutQuoteRepository(db).insert(session, q, customerId.value(),
                "digest-" + quoteId, "fingerprint-" + quoteId));
        return new Fixture(customerId, addressId, quoteId, 1);
    }

    private Document orderDoc(Order o) {
        return db.getCollection("orders").find(Filters.eq("_id", o.orderId().value())).first();
    }

    private long count(String collection) {
        return db.getCollection(collection).countDocuments();
    }

    private double counter(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        var c = search.counter();
        return c == null ? 0 : c.count();
    }

    private void assertNothingCommitted(Fixture f, Document cartBefore) {
        assertThat(count("orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        Document stock = db.getCollection("inventory").find(new Document("sku_id", SKU)).first();
        assertThat(stock.get("on_hand", Number.class).longValue()).isEqualTo(10);
        assertThat(stock.get("reserved", Number.class).longValue()).isZero();
        assertThat(db.getCollection("customer_carts").find(new Document("_id", f.customerId().value())).first())
                .isEqualTo(cartBefore);
    }

    private static void assertFailure(Callable<?> call, OrderFailure.Reason reason) {
        assertThatThrownBy(call::call).isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(reason));
    }

    // ============================================================
    // placement outcomes
    // ============================================================

    @Test
    void no_membership_creates_the_order_with_a_NO_MEMBERSHIP_snapshot() {
        Fixture f = fixture();

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.benefitSnapshot()).isEqualTo(
                new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
        Document stored = (Document) orderDoc(o).get("benefits");
        assertThat(stored.keySet()).containsExactlyInAnyOrder("outcome", "eligibleSubtotalPaise", "noBenefitReason");
        assertThat(stored.getString("noBenefitReason")).isEqualTo("NO_MEMBERSHIP");
        assertThat(OrderRepository.toOrder(orderDoc(o))).isEqualTo(o);
    }

    @Test
    void a_member_with_no_configured_rule_creates_the_order_with_a_NO_RULE_snapshot_the_production_default() {
        Fixture f = fixture();
        grant(f.customerId());

        Order o = service(realBenefits()).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.benefitSnapshot()).isEqualTo(
                new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NO_RULE));
        assertThat(counter("order_place_cod_success")).isEqualTo(1.0);
        assertThat(counter("order_place_cod_failure")).as("a normal no-benefit is never a failure").isZero();
    }

    @Test
    void a_member_below_the_threshold_creates_the_order_with_a_NOT_ELIGIBLE_snapshot() {
        Fixture f = fixture();
        grant(f.customerId());

        Order o = service(realBenefits(rule(SUBTOTAL + 1, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.benefitSnapshot()).isEqualTo(
                new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NOT_ELIGIBLE));
        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void a_member_at_the_threshold_creates_the_order_with_an_APPLIED_snapshot_equal_to_the_benefits_result() {
        Fixture f = fixture();
        Membership term = grant(f.customerId());
        TransactionalBenefitsEvaluationPort port = realBenefits(rule(SUBTOTAL, 500));

        BenefitEvaluation.Applied expected = (BenefitEvaluation.Applied) new Tx(client).call(
                session -> port.evaluate(session, f.customerId(), Money.ofInrPaise(SUBTOTAL)));
        Order o = service(port).placeCodOrder(f.customerId(), f.quoteId());

        OrderBenefitSnapshot.Applied snapshot = (OrderBenefitSnapshot.Applied) o.benefitSnapshot();
        assertThat(snapshot).isEqualTo(new OrderBenefitSnapshot.Applied(SUBTOTAL, 500, 500,
                term.membershipId().value(), PLAN, 1));
        assertThat(snapshot.eligibleSubtotalPaise()).isEqualTo(expected.eligibleSubtotal().paise());
        assertThat(snapshot.discountPaise()).isEqualTo(expected.discountAmount().paise());
        assertThat(snapshot.discountBps()).isEqualTo(expected.discountBpsValue());
        assertThat(snapshot.membershipId()).isEqualTo(expected.membershipIdValue());
        assertThat(snapshot.planId()).isEqualTo(expected.planId());
        assertThat(snapshot.planVersion()).isEqualTo(expected.planVersion());
        Document stored = (Document) orderDoc(o).get("benefits");
        assertThat(stored.getString("outcome")).isEqualTo("APPLIED");
        assertThat(stored.get("discountPaise")).isEqualTo(500L);
    }

    @Test
    void canonical_money_is_unchanged_and_no_discount_is_allocated_or_net_total_persisted() {
        Fixture f = fixture();
        grant(f.customerId());

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.subtotalPaise()).isEqualTo(SUBTOTAL);
        assertThat(o.lines()).hasSize(1);
        assertThat(o.lines().get(0).unitPricePaise()).isEqualTo(5000);
        assertThat(o.lines().get(0).lineTotalPaise()).isEqualTo(SUBTOTAL);
        Document doc = orderDoc(o);
        assertThat(doc.get("subtotalPaise")).isEqualTo(SUBTOTAL);
        assertThat(doc.keySet()).doesNotContain("discountPaise", "payablePaise", "amountDue", "grandTotal",
                "orderAmount", "finalTotal", "paymentAmount", "netSubtotalPaise");
        Document line = doc.getList("lines", Document.class).get(0);
        assertThat(line.keySet()).containsExactlyInAnyOrder("skuId", "title", "brandCode", "quantity",
                "unitPricePaise", "lineTotalPaise");
    }

    @Test
    void the_create_only_path_persists_the_snapshot_too() {
        Fixture f = fixture();
        grant(f.customerId());

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).createOrder(f.customerId(), f.quoteId(), PaymentMethod.COD);

        assertThat(o.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(o.benefitSnapshot()).isInstanceOf(OrderBenefitSnapshot.Applied.class);
        assertThat(OrderRepository.toOrder(orderDoc(o)).benefitSnapshot()).isEqualTo(o.benefitSnapshot());
    }

    // ============================================================
    // failure mapping: every Benefits failure aborts the whole transaction
    // ============================================================

    @Test
    void benefits_failures_map_onto_orders_vocabulary_and_roll_everything_back() {
        for (Object[] c : new Object[][]{
                {BenefitsFailure.Reason.UNAVAILABLE, OrderFailure.Reason.UNAVAILABLE},
                {BenefitsFailure.Reason.INTEGRITY_FAILURE, OrderFailure.Reason.INTEGRITY_FAILURE},
                {BenefitsFailure.Reason.INVALID_REQUEST, OrderFailure.Reason.INTEGRITY_FAILURE}}) {
            reset();
            Fixture f = fixture();
            Document cartBefore = db.getCollection("customer_carts").find(new Document("_id", f.customerId().value()))
                    .first();
            TransactionalBenefitsEvaluationPort failing = (s, cust, subtotal) -> {
                throw new BenefitsFailure((BenefitsFailure.Reason) c[0], "boom");
            };

            assertFailure(() -> service(failing).placeCodOrder(f.customerId(), f.quoteId()),
                    (OrderFailure.Reason) c[1]);

            assertNothingCommitted(f, cartBefore);
            assertThat(counter("order_place_cod_failure", "reason", ((OrderFailure.Reason) c[1]).name().toLowerCase()))
                    .isEqualTo(1.0);
            assertThat(counter("order_place_cod_success")).isZero();
        }
    }

    @Test
    void a_benefits_result_that_disagrees_with_the_order_subtotal_is_an_integrity_failure_and_rolls_back() {
        Fixture f = fixture();
        Document cartBefore = db.getCollection("customer_carts").find(new Document("_id", f.customerId().value()))
                .first();
        // a result that is internally consistent but about a DIFFERENT subtotal than the one Order evaluated
        TransactionalBenefitsEvaluationPort different = (s, cust, subtotal) -> new BenefitEvaluation.Applied(
                com.tazzzo.membership.MembershipId.generate(), PLAN, 1, Money.ofInrPaise(20_000),
                Money.ofInrPaise(1_000), new DiscountBps(500));

        assertFailure(() -> service(different).placeCodOrder(f.customerId(), f.quoteId()),
                OrderFailure.Reason.INTEGRITY_FAILURE);
        assertNothingCommitted(f, cartBefore);
    }

    // ============================================================
    // idempotent replay never re-evaluates
    // ============================================================

    @Test
    void a_replay_returns_the_stored_order_untouched_and_never_re_evaluates_benefits_after_a_membership_change() {
        Fixture f = fixture();
        Membership term = grant(f.customerId());
        Order first = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());
        Document before = orderDoc(first);
        assertThat(first.benefitSnapshot()).isInstanceOf(OrderBenefitSnapshot.Applied.class);

        termination.revoke(term.membershipId()); // Membership changed AFTER the first placement (committed)
        Order replay = service(MUST_NOT_BE_CALLED).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(replay).isEqualTo(first);
        assertThat(replay.benefitSnapshot()).isEqualTo(first.benefitSnapshot());
        assertThat(orderDoc(first)).as("the stored document is untouched").isEqualTo(before);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void a_replay_ignores_a_benefits_configuration_change_between_placements() {
        Fixture f = fixture();
        grant(f.customerId());
        Order first = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        // a later deployment loads different rules (a better rate, a lower threshold): replay must not follow it
        Order replay = service(realBenefits(rule(1_000, 9_000))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(replay).isEqualTo(first);
        assertThat(((OrderBenefitSnapshot.Applied) replay.benefitSnapshot()).discountBps()).isEqualTo(500);
    }

    @Test
    void a_stored_no_benefit_outcome_replays_unchanged_even_after_the_customer_becomes_a_member_with_a_rule() {
        Fixture f = fixture();
        Order first = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());
        assertThat(first.benefitSnapshot()).isEqualTo(
                new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));

        grant(f.customerId());
        Order replay = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(replay).isEqualTo(first);
        assertThat(replay.benefitSnapshot()).isInstanceOf(OrderBenefitSnapshot.NoBenefit.class);
    }

    // ============================================================
    // transaction semantics
    // ============================================================

    /** Delegates to a real port while recording the session it was handed and whether a transaction was active. */
    private static final class RecordingPort implements TransactionalBenefitsEvaluationPort {
        final TransactionalBenefitsEvaluationPort delegate;
        final AtomicReference<ClientSession> sessionSeen = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean transactionWasActive;

        RecordingPort(TransactionalBenefitsEvaluationPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public BenefitEvaluation evaluate(ClientSession session, CustomerId customerId, Money eligibleSubtotal) {
            calls.incrementAndGet();
            sessionSeen.set(session);
            transactionWasActive = session.hasActiveTransaction();
            return delegate.evaluate(session, customerId, eligibleSubtotal);
        }
    }

    @Test
    void benefits_is_evaluated_once_inside_the_orders_one_transaction_with_the_orders_session() {
        Fixture f = fixture();
        RecordingPort port = new RecordingPort(realBenefits(rule(SUBTOTAL, 500)));
        AtomicReference<ClientSession> orderSession = new AtomicReference<>();
        Tx tx = new Tx(client) {
            @Override
            public <T> T call(java.util.function.Function<ClientSession, T> body) {
                return super.call(session -> {
                    orderSession.set(session);
                    return body.apply(session);
                });
            }
        };

        service(tx, port).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(port.calls.get()).isEqualTo(1);
        assertThat(port.transactionWasActive).as("evaluated inside the caller's transaction").isTrue();
        assertThat(port.sessionSeen.get()).as("the ORDER's session, no nested transaction").isSameAs(orderSession.get());
    }

    @Test
    void a_driver_retry_of_the_outer_transaction_re_evaluates_but_commits_exactly_one_order_and_one_metric() {
        Fixture f = fixture();
        grant(f.customerId());
        RecordingPort port = new RecordingPort(realBenefits(rule(SUBTOTAL, 500)));
        RetryInjectingTx retrying = new RetryInjectingTx(client).arm(1);

        Order o = service(retrying, port).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(port.calls.get()).as("the outer Tx owns retry; no second retry layer exists").isEqualTo(2);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(o.benefitSnapshot()).isInstanceOf(OrderBenefitSnapshot.Applied.class);
        assertThat(counter("order_place_cod_success")).isEqualTo(1.0);
        assertThat(counter("order_place_cod_failure")).isZero();
    }

    @Test
    void a_transient_error_from_benefits_reaches_the_outer_transaction_retry_untouched() {
        Fixture f = fixture();
        AtomicInteger attempts = new AtomicInteger();
        TransactionalBenefitsEvaluationPort flaky = (session, cust, subtotal) -> {
            if (attempts.incrementAndGet() == 1) {
                MongoException transientError = new MongoException("simulated transient error");
                transientError.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
                throw transientError;
            }
            return realBenefits(rule(SUBTOTAL, 500)).evaluate(session, cust, subtotal);
        };

        Order o = service(flaky).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void a_membership_revoke_in_the_same_transaction_before_evaluation_is_visible_to_the_snapshot() {
        Fixture f = fixture();
        Membership term = grant(f.customerId());
        TransactionalBenefitsEvaluationPort real = realBenefits(rule(SUBTOTAL, 500));
        Instant revokedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // the SAME session, an uncommitted valid revoke, then the real evaluation -- no production hook involved
        TransactionalBenefitsEvaluationPort revokeThenEvaluate = (session, cust, subtotal) -> {
            db.getCollection("memberships").updateOne(session, Filters.eq("_id", term.membershipId().value()),
                    Updates.combine(Updates.set("status", "REVOKED"), Updates.set("revokedAt", Date.from(revokedAt)),
                            Updates.set("updatedAt", Date.from(revokedAt)), Updates.set("version", 2L),
                            Updates.unset("openTerm")));
            return real.evaluate(session, cust, subtotal);
        };

        Order o = service(revokeThenEvaluate).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.benefitSnapshot()).isEqualTo(
                new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
        assertThat(db.getCollection("memberships").find(Filters.eq("_id", term.membershipId().value())).first()
                .getString("status")).isEqualTo("REVOKED");
    }

    @Test
    void a_membership_revoke_committed_before_placement_is_seen_as_no_membership() {
        Fixture f = fixture();
        Membership term = grant(f.customerId());
        termination.revoke(term.membershipId());

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.benefitSnapshot()).isEqualTo(
                new OrderBenefitSnapshot.NoBenefit(SUBTOTAL, BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP));
    }

    @Test
    void benefits_adds_no_metric_and_the_order_metrics_carry_no_benefits_values() {
        Fixture f = fixture();
        grant(f.customerId());

        service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(registry.getMeters().stream().map(m -> m.getId().getName()))
                .containsExactly("order_place_cod_success");
        registry.getMeters().stream().map(Meter::getId).forEach(id -> assertThat(id.getTags()).isEmpty());
    }

    @Test
    void the_order_is_not_inserted_without_a_benefits_snapshot_every_new_order_carries_one() {
        for (int i = 0; i < 3; i++) {
            reset();
            Fixture f = fixture();
            Order o = service(i == 0 ? realBenefits() : realBenefits(rule(SUBTOTAL, 500)))
                    .placeCodOrder(f.customerId(), f.quoteId());
            assertThat(orderDoc(o).containsKey("benefits")).isTrue();
            assertThat(o.benefitSnapshot()).isNotNull();
        }
    }

    // ============================================================
    // PR-18A-1 hardening: the IN-TRANSACTION replay and duplicate-key recovery never (re)evaluate Benefits
    // ============================================================

    /** Counts every call on the collaborator by method name, delegating to the real one. */
    @SuppressWarnings("unchecked")
    private static <T> T counting(Class<T> type, T delegate, Map<String, AtomicInteger> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            calls.computeIfAbsent(method.getName(), k -> new AtomicInteger()).incrementAndGet();
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    private static int calls(Map<String, AtomicInteger> calls, String method) {
        AtomicInteger c = calls.get(method);
        return c == null ? 0 : c.get();
    }

    /** TEST-ONLY seam: hides the Order from the PRE-transaction fast-path read ONLY, so the placement proceeds
     *  to its IN-TRANSACTION durable replay check, and records that check finding the stored Order. */
    private static final class FastPathBlindOrders extends OrderRepository {
        final AtomicInteger fastPathReads = new AtomicInteger();
        final AtomicInteger inTransactionHits = new AtomicInteger();

        FastPathBlindOrders(MongoDatabase db) {
            super(db);
        }

        @Override public Document findByCustomerAndQuote(String customerId, String quoteId) {
            fastPathReads.incrementAndGet();
            return null; // the "race": the fast path did not see the Order yet
        }

        @Override public Document findByCustomerAndQuote(ClientSession session, String customerId, String quoteId) {
            Document found = super.findByCustomerAndQuote(session, customerId, quoteId);
            if (found != null) {
                inTransactionHits.incrementAndGet();
            }
            return found;
        }
    }

    @Test
    void the_in_transaction_replay_returns_the_stored_order_without_benefits_pricing_inventory_or_cart_work() {
        Fixture f = fixture();
        Membership term = grant(f.customerId());
        Order first = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());
        termination.revoke(term.membershipId()); // a re-evaluation WOULD now produce a different outcome
        Document orderBefore = orderDoc(first);
        Document cartBefore = db.getCollection("customer_carts").find(new Document("_id", f.customerId().value()))
                .first();
        Document stockBefore = db.getCollection("inventory").find(new Document("sku_id", SKU)).first();
        long reservationsBefore = count("inventory_reservations");
        Document reservationBefore = db.getCollection("inventory_reservations")
                .find(new Document("_id", first.reservationId())).first();

        Tx tx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Map<String, AtomicInteger> pricingCalls = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> inventoryCalls = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> cartCalls = new ConcurrentHashMap<>();
        AtomicInteger benefitsCalls = new AtomicInteger();
        TransactionalBenefitsEvaluationPort mustNotRun = (s, c, subtotal) -> {
            benefitsCalls.incrementAndGet();
            throw new AssertionError("Benefits must NOT be evaluated once the in-transaction replay found the Order");
        };
        FastPathBlindOrders orders = new FastPathBlindOrders(db);
        OrderService svc = service(tx, orders,
                counting(TransactionalPriceReadPort.class, new PricingService(tx, new WritePath(db), clock),
                        pricingCalls),
                counting(InventoryReservationPort.class, reservationService(tx, clock), inventoryCalls),
                counting(CartPurchasePort.class, new CartPurchaseService(new CartRepository(db), clock), cartCalls),
                mustNotRun);

        Order replay = svc.placeCodOrder(f.customerId(), f.quoteId());

        assertThat(orders.fastPathReads.get()).as("the pre-transaction fast path ran and missed").isEqualTo(1);
        assertThat(orders.inTransactionHits.get()).as("the IN-TRANSACTION check found the Order").isEqualTo(1);
        assertThat(replay).isEqualTo(first);
        assertThat(replay.benefitSnapshot()).isInstanceOf(OrderBenefitSnapshot.Applied.class);
        assertThat(replay.moneySnapshot()).as("the stored winner's money snapshot, unchanged")
                .isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 500));
        assertThat(replay.moneySnapshot().payablePaise()).isEqualTo(9_500);
        assertThat(benefitsCalls.get()).as("Benefits NOT evaluated").isZero();
        assertThat(calls(pricingCalls, "findCurrentPrices") + calls(pricingCalls, "findCurrentPrice"))
                .as("no Pricing revalidation").isZero();
        assertThat(calls(inventoryCalls, "reserve") + calls(inventoryCalls, "consume") + calls(inventoryCalls, "release"))
                .as("no Inventory reserve/consume/release").isZero();
        assertThat(calls(cartCalls, "isSourceVersionPurchased") + calls(cartCalls, "finalizePurchase"))
                .as("no cart guard or mutation").isZero();
        assertThat(orderDoc(first)).as("the stored Order is untouched").isEqualTo(orderBefore);
        assertThat(db.getCollection("customer_carts").find(new Document("_id", f.customerId().value())).first())
                .isEqualTo(cartBefore);
        assertThat(db.getCollection("inventory").find(new Document("sku_id", SKU)).first()).isEqualTo(stockBefore);
        assertThat(count("inventory_reservations")).isEqualTo(reservationsBefore);
        assertThat(db.getCollection("inventory_reservations").find(new Document("_id", first.reservationId())).first())
                .isEqualTo(reservationBefore);
        assertThat(count("orders")).isEqualTo(1);
    }

    /** TEST-ONLY. Reproduces a lost race (same shape as OrderPlaceCodIT's): both replay reads saw "no Order yet", the
     *  insert fails with a synthetic 11000, and afterwards the reads see the real collection (what recovery re-reads). */
    private static final class DuplicateKeyOnInsertOrders extends OrderRepository {
        private volatile boolean hideExisting = true;
        final AtomicInteger recoveryReads = new AtomicInteger();

        DuplicateKeyOnInsertOrders(MongoDatabase db) {
            super(db);
        }

        @Override public Document findByCustomerAndQuote(String customerId, String quoteId) {
            if (hideExisting) {
                return null;
            }
            recoveryReads.incrementAndGet();
            return super.findByCustomerAndQuote(customerId, quoteId);
        }

        @Override public Document findByCustomerAndQuote(ClientSession session, String customerId, String quoteId) {
            return hideExisting ? null : super.findByCustomerAndQuote(session, customerId, quoteId);
        }

        @Override public void insert(ClientSession session, Order order) {
            hideExisting = false;
            throw new MongoWriteException(new WriteError(11000, "E11000", new BsonDocument()), new ServerAddress());
        }
    }

    @Test
    void duplicate_key_recovery_returns_the_winners_snapshot_unchanged_and_never_evaluates_benefits_again() {
        Fixture f = fixture();
        Membership term = grant(f.customerId());
        Order winner = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());
        assertThat(winner.benefitSnapshot()).isInstanceOf(OrderBenefitSnapshot.Applied.class);
        Document winnerDoc = orderDoc(winner);
        // the loser passes every guard and reaches its insert: put cart and stock back to their pre-placement state
        db.getCollection("customer_carts").deleteMany(new Document());
        db.getCollection("customer_carts").insertOne(new Document("_id", f.customerId().value())
                .append("items", List.of(new Document("skuId", SKU).append("quantity", 2)
                        .append("addedAt", Date.from(NOW)).append("updatedAt", Date.from(NOW))))
                .append("version", f.cartVersion()).append("createdAt", Date.from(NOW))
                .append("updatedAt", Date.from(NOW)).append("expiresAt", Date.from(NOW.plusSeconds(7 * 86400))));
        db.getCollection("inventory").updateOne(new Document("sku_id", SKU),
                new Document("$set", new Document("on_hand", 10L).append("reserved", 0L)));
        termination.revoke(term.membershipId()); // the loser's own evaluation now differs from the winner's

        Tx tx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AtomicInteger loserEvaluations = new AtomicInteger();
        TransactionalBenefitsEvaluationPort loserBenefits = (session, cust, subtotal) -> {
            loserEvaluations.incrementAndGet();
            return realBenefits(rule(SUBTOTAL, 500)).evaluate(session, cust, subtotal);
        };
        DuplicateKeyOnInsertOrders orders = new DuplicateKeyOnInsertOrders(db);
        OrderService loser = service(tx, orders, new PricingService(tx, new WritePath(db), clock),
                reservationService(tx, clock), new CartPurchaseService(new CartRepository(db), clock), loserBenefits);

        Order result = loser.placeCodOrder(f.customerId(), f.quoteId());

        assertThat(result).as("the WINNER's stored Order is returned").isEqualTo(winner);
        assertThat(result.benefitSnapshot()).as("its snapshot is the winner's, not the loser's recalculation")
                .isEqualTo(winner.benefitSnapshot());
        assertThat(result.moneySnapshot()).as("the winner's money, never the loser's (which would be payable 10000)")
                .isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 500));
        assertThat(result.moneySnapshot().payablePaise()).isEqualTo(9_500);
        assertThat(loserEvaluations.get()).as("exactly the loser's one pre-insert evaluation; recovery adds none")
                .isEqualTo(1);
        assertThat(orders.recoveryReads.get()).as("recovery re-read the durable winner").isEqualTo(1);
        assertThat(orderDoc(winner)).as("the winner's persisted snapshot is not recalculated or replaced")
                .isEqualTo(winnerDoc);
        assertThat(count("orders")).isEqualTo(1);
    }

    // ============================================================
    // PR-20A: the authoritative V1 money snapshot (payable = merchandise subtotal - benefit discount)
    // ============================================================

    private Document moneyDoc(Order o) {
        return (Document) orderDoc(o).get("money");
    }

    @Test
    void a_no_benefit_order_stores_payable_equal_to_the_canonical_subtotal() {
        Fixture f = fixture();

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.benefitSnapshot()).isInstanceOf(OrderBenefitSnapshot.NoBenefit.class);
        assertThat(o.moneySnapshot()).isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 0));
        assertThat(o.moneySnapshot().merchandiseSubtotalPaise()).isEqualTo(o.subtotalPaise());
        assertThat(o.moneySnapshot().benefitDiscountPaise()).isZero();
        assertThat(o.moneySnapshot().payablePaise()).isEqualTo(SUBTOTAL);
        assertThat(moneyDoc(o).keySet()).containsExactlyInAnyOrder("merchandiseSubtotalPaise", "benefitDiscountPaise",
                "payablePaise");
        assertThat(moneyDoc(o).get("payablePaise")).isEqualTo(SUBTOTAL);
    }

    @Test
    void an_applied_benefit_order_stores_subtotal_minus_the_authoritative_discount_as_payable() {
        Fixture f = fixture();
        grant(f.customerId());

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        OrderBenefitSnapshot.Applied applied = (OrderBenefitSnapshot.Applied) o.benefitSnapshot();
        assertThat(o.moneySnapshot()).isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 500));
        assertThat(o.moneySnapshot().benefitDiscountPaise()).as("the SAME discount as the benefit snapshot")
                .isEqualTo(applied.discountPaise());
        assertThat(o.moneySnapshot().merchandiseSubtotalPaise()).isEqualTo(applied.eligibleSubtotalPaise());
        assertThat(o.moneySnapshot().payablePaise()).isEqualTo(9_500);
        assertThat(moneyDoc(o).get("benefitDiscountPaise")).isEqualTo(500L);
        assertThat(moneyDoc(o).get("payablePaise")).isEqualTo(9_500L);
        // canonical money is untouched
        assertThat(o.subtotalPaise()).isEqualTo(SUBTOTAL);
        assertThat(o.lines().get(0).unitPricePaise()).isEqualTo(5_000);
        assertThat(o.lines().get(0).lineTotalPaise()).isEqualTo(SUBTOTAL);
    }

    @Test
    void a_100_percent_benefit_is_a_valid_zero_payable_order_with_unchanged_payment_semantics() {
        Fixture f = fixture();
        grant(f.customerId());

        Order o = service(realBenefits(new BenefitRule(PLAN, 1, Money.ofInrPaise(SUBTOTAL), new DiscountBps(10_000))))
                .placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.moneySnapshot()).isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, SUBTOTAL));
        assertThat(o.moneySnapshot().payablePaise()).as("zero payable is valid").isZero();
        assertThat(moneyDoc(o).get("payablePaise")).isEqualTo(0L);
        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.paymentMethod()).isEqualTo(PaymentMethod.COD);
        assertThat(o.confirmedPaymentCondition()).as("no new payment state").isEqualTo(ConfirmedPaymentCondition.COD_DUE);
        assertThat(OrderRepository.toOrder(orderDoc(o))).isEqualTo(o);
    }

    @Test
    void every_new_order_path_persists_a_money_snapshot_consistent_with_its_benefit_snapshot() {
        for (int i = 0; i < 2; i++) {
            reset();
            Fixture f = fixture();
            grant(f.customerId());
            OrderService svc = service(realBenefits(rule(SUBTOTAL, 500)));
            Order o = i == 0 ? svc.placeCodOrder(f.customerId(), f.quoteId())
                    : svc.createOrder(f.customerId(), f.quoteId(), PaymentMethod.COD);

            assertThat(orderDoc(o).containsKey("money")).as(o.status().name()).isTrue();
            assertThat(o.moneySnapshot()).isNotNull();
            assertThat(OrderRepository.toOrder(orderDoc(o)).moneySnapshot()).isEqualTo(o.moneySnapshot());
        }
    }

    @Test
    void a_fast_replay_returns_the_stored_money_unchanged_after_a_membership_and_configuration_change() {
        Fixture f = fixture();
        Membership term = grant(f.customerId());
        Order first = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());
        termination.revoke(term.membershipId());

        Order replay = service(MUST_NOT_BE_CALLED).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(replay.moneySnapshot()).isEqualTo(first.moneySnapshot());
        assertThat(replay.moneySnapshot().payablePaise()).isEqualTo(9_500);
        Order afterConfigChange = service(realBenefits(rule(1_000, 9_000))).placeCodOrder(f.customerId(), f.quoteId());
        assertThat(afterConfigChange.moneySnapshot().payablePaise()).isEqualTo(9_500);
        assertThat(moneyDoc(first).get("payablePaise")).as("the stored payable is untouched").isEqualTo(9_500L);
    }

    // ============================================================
    // The quote's money is ADVISORY: the Order computes its own AUTHORITATIVE money and may differ (both directions)
    // ============================================================

    /**
     * A quote that carries an ADVISORY Benefits snapshot and money snapshot showing {@code quoteDiscountPaise} on the
     * 2 x 5000 basket (0 = no benefit shown), exactly as a real Checkout persists them.
     */
    private Fixture fixtureQuoted(long quoteDiscountPaise) {
        CustomerId customerId = CustomerId.generate();
        String addressId = AddressId.generate().value();
        seed(customerId.value(), addressId, 1);
        String quoteId = CheckoutQuoteId.generate().value();
        com.tazzzo.customer.checkout.CheckoutBenefitSnapshot benefit = quoteDiscountPaise == 0
                ? new com.tazzzo.customer.checkout.CheckoutBenefitSnapshot.NoBenefit(SUBTOTAL,
                        BenefitEvaluation.NoBenefitReason.NO_MEMBERSHIP)
                : new com.tazzzo.customer.checkout.CheckoutBenefitSnapshot.Applied(SUBTOTAL, quoteDiscountPaise,
                        (int) (quoteDiscountPaise * 10_000 / SUBTOTAL));
        CheckoutQuote q = new CheckoutQuote(quoteId, 1, addressId, 1L,
                List.of(new CheckoutQuote.Line(SKU, 2, 5000, SUBTOTAL)), 2, SUBTOTAL, "INR", NOW,
                NOW.plusSeconds(600), benefit,
                new com.tazzzo.customer.checkout.CheckoutMoneySnapshot(SUBTOTAL, quoteDiscountPaise));
        new Tx(client).run(session -> new CheckoutQuoteRepository(db).insert(session, q, customerId.value(),
                "digest-" + quoteId, "fingerprint-" + quoteId));
        return new Fixture(customerId, addressId, quoteId, 1);
    }

    /** The quote's persisted advisory {@code money} document, or {@code null} when the quote has none. */
    private Document storedQuoteMoney(Fixture f) {
        return (Document) db.getCollection("checkout_quotes").find(new Document("_id", f.quoteId())).first().get("money");
    }

    @Test
    void a_benefit_lost_after_the_quote_still_places_and_the_order_money_is_the_authoritative_10000() {
        Fixture f = fixtureQuoted(500);                             // the quote showed 10000 / 500 / 9500
        Membership term = grant(f.customerId());
        termination.revoke(term.membershipId());                    // then the benefit is gone

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.moneySnapshot()).isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 0));
        assertThat(o.moneySnapshot().payablePaise()).isEqualTo(10_000);
        assertThat(storedQuoteMoney(f).get("payablePaise")).as("the advisory quote money is untouched").isEqualTo(9_500L);
        assertThat(storedQuoteMoney(f).get("benefitDiscountPaise")).isEqualTo(500L);
        assertThat(counter("order_place_cod_success")).isEqualTo(1.0);
    }

    @Test
    void a_benefit_gained_after_the_quote_still_places_and_the_customer_gets_the_lower_authoritative_payable() {
        Fixture f = fixtureQuoted(0);                               // the quote showed 10000 / 0 / 10000
        grant(f.customerId());                                      // a benefit now applies

        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.moneySnapshot()).isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 500));
        assertThat(o.moneySnapshot().payablePaise()).isEqualTo(9_500);
        assertThat(storedQuoteMoney(f).get("payablePaise")).isEqualTo(10_000L);
    }

    @Test
    void a_benefits_configuration_change_after_the_quote_is_won_by_the_order() {
        Fixture f = fixtureQuoted(500);                             // the quote showed a 5% discount
        grant(f.customerId());

        Order o = service(realBenefits(rule(SUBTOTAL, 2_000))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.moneySnapshot()).as("the rule in force at placement wins").isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 2_000));
        assertThat(o.moneySnapshot().payablePaise()).isEqualTo(8_000);
    }

    @Test
    void a_zero_payable_can_appear_between_quote_and_order_without_any_error() {
        Fixture f = fixtureQuoted(0);                               // quote payable 10000 ...
        grant(f.customerId());

        Order o = service(realBenefits(rule(SUBTOTAL, 10_000))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.moneySnapshot().payablePaise()).as("... order payable 0").isZero();
        assertThat(o.confirmedPaymentCondition()).isEqualTo(ConfirmedPaymentCondition.COD_DUE);
    }

    @Test
    void a_zero_payable_can_disappear_between_quote_and_order_without_any_error() {
        Fixture f = fixtureQuoted(SUBTOTAL);                        // quote payable 0 ... (no membership at placement)

        Order o = service(realBenefits(rule(SUBTOTAL, 10_000))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.moneySnapshot().payablePaise()).as("... order payable 10000").isEqualTo(10_000);
    }

    @Test
    void a_legacy_quote_without_advisory_money_places_normally() {
        Fixture f = fixture();                                      // no benefit snapshot, no money snapshot

        assertThat(storedQuoteMoney(f)).as("a legacy quote carries no advisory money").isNull();
        Order o = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.moneySnapshot()).isEqualTo(OrderMoneySnapshot.from(SUBTOTAL, 0));
    }

    @Test
    void a_changed_line_price_is_still_PRICE_CHANGED_whatever_the_quote_money_shows() {
        Fixture f = fixtureQuoted(500);
        db.getCollection("price_current").updateOne(new Document("sku_id", SKU),
                new Document("$set", new Document("selling_price_paise", 5100L)));
        Document cartBefore = db.getCollection("customer_carts").find(new Document("_id", f.customerId().value())).first();

        assertFailure(() -> service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId()),
                OrderFailure.Reason.PRICE_CHANGED);

        assertNothingCommitted(f, cartBefore);
    }

    @Test
    void a_replay_after_the_quote_expired_still_returns_the_stored_order() {
        Fixture f = fixtureQuoted(500);
        grant(f.customerId());
        Order first = service(realBenefits(rule(SUBTOTAL, 500))).placeCodOrder(f.customerId(), f.quoteId());
        db.getCollection("checkout_quotes").updateOne(new Document("_id", f.quoteId()),
                new Document("$set", new Document("expiresAt", Date.from(NOW.minusSeconds(1)))));

        assertThat(service(MUST_NOT_BE_CALLED).placeCodOrder(f.customerId(), f.quoteId())).isEqualTo(first);
    }
}
