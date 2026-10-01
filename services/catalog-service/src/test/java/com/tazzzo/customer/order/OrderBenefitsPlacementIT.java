package com.tazzzo.customer.order;

import com.mongodb.MongoException;
import com.mongodb.client.ClientSession;
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
import com.tazzzo.customer.cart.CartPurchaseService;
import com.tazzzo.customer.cart.CartRepository;
import com.tazzzo.customer.checkout.CheckoutQuote;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.customer.checkout.CheckoutQuoteRepository;
import com.tazzzo.inventory.InventoryReservationObservability;
import com.tazzzo.inventory.InventoryReservationProperties;
import com.tazzzo.inventory.InventoryReservationRepository;
import com.tazzzo.inventory.InventoryReservationService;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.membership.Membership;
import com.tazzzo.membership.MembershipService;
import com.tazzzo.membership.MembershipTerminationService;
import com.tazzzo.membership.TransactionalMembershipEntitlementPort;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.serviceability.ServiceabilityService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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

    private OrderService service(Tx tx, TransactionalBenefitsEvaluationPort benefits) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        WritePath writePath = new WritePath(db);
        InventoryReservationProperties p = new InventoryReservationProperties();
        p.setTtlSeconds(600);
        p.setExpiryBatchSize(100);
        InventoryReservationService reservations = new InventoryReservationService(
                new InventoryService(tx, writePath, clock), new InventoryReservationRepository(db), p,
                new InventoryReservationObservability(new SimpleMeterRegistry()), clock, tx);
        return new OrderService(new OrderRepository(db), new CheckoutQuoteRepository(db), new AddressRepository(db),
                new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock),
                new PricingService(tx, writePath, clock), new com.tazzzo.commerce.read.CatalogCardReader(db), benefits,
                reservations, new CartPurchaseService(new CartRepository(db), clock), clock, ALWAYS_EXISTS, tx,
                new OrderObservability(registry));
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
}
