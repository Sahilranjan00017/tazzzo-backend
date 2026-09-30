package com.tazzzo.customer.order;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.address.AddressRepository;
import com.tazzzo.customer.cart.CartPurchaseOutcome;
import com.tazzzo.customer.cart.CartPurchasePort;
import com.tazzzo.customer.cart.CartPurchaseService;
import com.tazzzo.customer.cart.CartRepository;
import com.tazzzo.customer.checkout.CheckoutQuote;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.customer.checkout.CheckoutQuoteRepository;
import com.tazzzo.inventory.InventoryReservation;
import com.tazzzo.inventory.InventoryReservationAllocation;
import com.tazzzo.inventory.InventoryReservationExpiryWorker;
import com.tazzzo.inventory.InventoryReservationFailure;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.inventory.InventoryReservationItem;
import com.tazzzo.inventory.InventoryReservationObservability;
import com.tazzzo.inventory.InventoryReservationPort;
import com.tazzzo.inventory.InventoryReservationProperties;
import com.tazzzo.inventory.InventoryReservationRepository;
import com.tazzzo.inventory.InventoryReservationService;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.PreparedInventoryReservation;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.serviceability.ServiceabilityService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-15A-1 — COD placement ({@link OrderService#placeCodOrder}) against a real Mongo transaction
 * (Testcontainers). Every scenario asserts COMMITTED state, never just a return value: the one-
 * transaction invariant (Order CONFIRMED + reservation CONSUMED + stock moved once + cart marker
 * raised + cart cleared only when still current) must hold together or not at all. No sleeps:
 * {@link RetryInjectingTx} for forced driver retries, a movable clock, latch rendezvous INSIDE the
 * transaction (after each contender's snapshot read) for real concurrency, and collaborator
 * subclasses for failure injection at an exact step.
 */
@SpringBootTest(classes = CatalogApplication.class)
class OrderPlaceCodIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final String PIN = "560001";
    private static final String LOC = "FL-COD-1";
    private static final String SKU = "TZP-COD1";

    private MeterRegistry registry;

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

    private static final class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static InventoryReservationProperties props(int ttlSeconds) {
        InventoryReservationProperties p = new InventoryReservationProperties();
        p.setTtlSeconds(ttlSeconds);
        p.setExpiryBatchSize(100);
        return p;
    }

    private InventoryReservationService reservationService(Tx tx, Clock clock, int ttlSeconds) {
        return new InventoryReservationService(new InventoryService(tx, new WritePath(db), clock),
                new InventoryReservationRepository(db), props(ttlSeconds),
                new InventoryReservationObservability(new SimpleMeterRegistry()), clock, tx);
    }

    private CartPurchasePort realCart(Clock clock) {
        return new CartPurchaseService(new CartRepository(db), clock);
    }

    private OrderService service(Tx tx, Clock clock, OrderRepository orderRepo, InventoryReservationPort port,
                                 CartPurchasePort cart) {
        WritePath writePath = new WritePath(db);
        return new OrderService(orderRepo, new CheckoutQuoteRepository(db), new AddressRepository(db),
                new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock),
                new PricingService(tx, writePath, clock), new com.tazzzo.commerce.read.CatalogCardReader(db), port,
                cart, clock, ALWAYS_EXISTS, tx, new OrderObservability(registry));
    }

    private OrderService service() {
        Tx tx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return service(tx, clock, new OrderRepository(db), reservationService(tx, clock, 600), realCart(clock));
    }

    // ---------- seeding ----------

    private void seedAddress(String addressId, String customerId, long version, String line1) {
        db.getCollection("customer_addresses").insertOne(new Document("_id", addressId)
                .append("customerId", customerId).append("label", "HOME").append("recipientName", "Test Recipient")
                .append("recipientPhone", "9999999999").append("addressLine1", line1)
                .append("addressLine2", "Near Landmark").append("landmark", "Landmark")
                .append("city", "Bengaluru").append("state", "Karnataka").append("postalCode", PIN)
                .append("latitude", 12.9716).append("longitude", 77.5946).append("version", version)
                .append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW)));
    }

    private void seedServiceArea(String fulfillmentLocationId) {
        db.getCollection("service_areas").insertOne(new Document("pincode", PIN)
                .append("service_area_id", "SA-1").append("active", true)
                .append("routes", List.of(new Document("fulfillment_location_id", fulfillmentLocationId)
                        .append("priority", 1).append("active", true)))
                .append("version", 1L).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
    }

    private void seedPrice(long sellingPaise) {
        db.getCollection("price_current").insertOne(new Document("sku_id", SKU)
                .append("currency", "INR").append("selling_price_paise", sellingPaise).append("mrp_paise", 6000L)
                .append("version", 1L).append("active", true).append("effective_from", null)
                .append("effective_to", null).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
    }

    private void seedProduct() {
        Document classification = new Document("vertical_id", "V-1").append("release_id", "R1")
                .append("status", "confirmed");
        db.getCollection("products").insertOne(new Document("_id", SKU).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", SKU))
                .append("brand_code", "BR-1").append("title", "COD Widget")
                .append("lifecycle", "active").append("classification", classification)
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date()));
    }

    private void seedStock(String loc, long onHand) {
        db.getCollection("inventory").insertOne(new Document("sku_id", SKU)
                .append("fulfillment_location_id", loc).append("on_hand", onHand).append("reserved", 0L)
                .append("low_stock_threshold", 1L).append("max_purchasable", 100L).append("version", 1L)
                .append("active", true).append("source", "seed").append("created_at", new Date())
                .append("updated_at", new Date()));
    }

    private void seedCart(String customerId, long version) {
        db.getCollection("customer_carts").insertOne(new Document("_id", customerId)
                .append("items", List.of(new Document("skuId", SKU).append("quantity", 2)
                        .append("addedAt", Date.from(NOW)).append("updatedAt", Date.from(NOW))))
                .append("version", version).append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW))
                .append("expiresAt", Date.from(NOW.plusSeconds(7 * 86400))));
    }

    private void insertQuote(String quoteId, CustomerId customerId, String addressId, long cartVersion,
                             Instant expiresAt) {
        CheckoutQuote q = new CheckoutQuote(quoteId, cartVersion, addressId, 1L,
                List.of(new CheckoutQuote.Line(SKU, 2, 5000, 10000)), 2, 10000, "INR", NOW, expiresAt);
        new Tx(client).run(session -> new CheckoutQuoteRepository(db).insert(session, q, customerId.value(),
                "digest-" + quoteId, "fingerprint-" + quoteId));
    }

    private record Fixture(CustomerId customerId, String addressId, CheckoutQuoteId quoteId, long cartVersion) { }

    /** One customer with cart at {@code cartVersion}, one address, one route, stock 10, and one unexpired
     *  quote (2 x 5000) taken from that cart version. */
    private Fixture fixture(long cartVersion) {
        CustomerId customerId = CustomerId.generate();
        String addressId = AddressId.generate().value();
        seedAddress(addressId, customerId.value(), 1L, "123 Test Street");
        seedServiceArea(LOC);
        seedPrice(5000);
        seedProduct();
        seedStock(LOC, 10);
        seedCart(customerId.value(), cartVersion);
        CheckoutQuoteId quoteId = CheckoutQuoteId.generate();
        insertQuote(quoteId.value(), customerId, addressId, cartVersion, NOW.plusSeconds(600));
        return new Fixture(customerId, addressId, quoteId, cartVersion);
    }

    private CheckoutQuoteId anotherQuote(Fixture f, long cartVersion) {
        CheckoutQuoteId id = CheckoutQuoteId.generate();
        insertQuote(id.value(), f.customerId(), f.addressId(), cartVersion, NOW.plusSeconds(600));
        return id;
    }

    // ---------- committed-state readers ----------

    private Document stock(String loc) {
        return db.getCollection("inventory").find(new Document("sku_id", SKU)
                .append("fulfillment_location_id", loc)).first();
    }

    private long onHand(String loc) { return stock(loc).get("on_hand", Number.class).longValue(); }

    private long reserved(String loc) { return stock(loc).get("reserved", Number.class).longValue(); }

    private Document cart(Fixture f) {
        return db.getCollection("customer_carts").find(new Document("_id", f.customerId().value())).first();
    }

    private long marker(Document cart) {
        Number n = cart.get("purchasedThroughVersion", Number.class);
        return n == null ? 0L : n.longValue();
    }

    private long count(String collection) { return db.getCollection(collection).countDocuments(); }

    private Document reservationDoc(String reservationId) {
        return db.getCollection("inventory_reservations").find(new Document("_id", reservationId)).first();
    }

    private double counter(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        var c = search.counter();
        return c == null ? 0 : c.count();
    }

    /** Nothing committed: no Order, no reservation, stock untouched, cart untouched, marker absent. */
    private void assertNothingCommitted(Fixture f, Document cartBefore) {
        assertThat(count("orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        assertThat(onHand(LOC)).isEqualTo(10);
        assertThat(reserved(LOC)).isZero();
        assertThat(cart(f)).isEqualTo(cartBefore);
        assertThat(cart(f).containsKey("purchasedThroughVersion")).isEqualTo(cartBefore.containsKey("purchasedThroughVersion"));
    }

    private static void assertFailure(Callable<?> call, OrderFailure.Reason reason) {
        assertThatThrownBy(call::call).isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(reason));
    }

    // ============================================================
    // 1-13: the core invariant on a fresh COD placement
    // ============================================================

    @Test void a_fresh_cod_placement_commits_the_whole_invariant_together() {
        Fixture f = fixture(1);
        Order o = service().placeCodOrder(f.customerId(), f.quoteId().value());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);                    // 1
        assertThat(o.version()).isEqualTo(2);                                        // 2
        assertThat(o.paymentMethod()).isEqualTo(PaymentMethod.COD);                  // 3
        assertThat(o.confirmedPaymentCondition()).isEqualTo(ConfirmedPaymentCondition.COD_DUE); // 4
        assertThat(o.confirmedAt()).isNotNull().isAfterOrEqualTo(o.createdAt());     // 5
        assertThat(o.updatedAt()).isEqualTo(o.confirmedAt());

        Document reservation = reservationDoc(o.reservationId());
        assertThat(reservation.getString("status")).isEqualTo("CONSUMED");           // 6
        assertThat(reservation.getString("orderId")).isEqualTo(o.orderId().value());
        assertThat(onHand(LOC)).isEqualTo(8);                                        // 7: decremented once
        assertThat(reserved(LOC)).isZero();                                          // 8: reserved back to 0, never stuck
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("inventory_reservations")).isEqualTo(1);

        Document cart = cart(f);                                                      // 9-11
        assertThat(cart.getList("items", Document.class)).isEmpty();
        assertThat(cart.get("version", Number.class).longValue()).isEqualTo(2);       // advanced exactly once
        assertThat(marker(cart)).isEqualTo(1);
        assertThat(cart.containsKey("_id")).isTrue();                                 // never deleted

        Document stored = db.getCollection("orders").find(new Document("_id", o.orderId().value())).first();
        assertThat(OrderRepository.toOrder(stored)).isEqualTo(o);                     // committed == returned
        assertThat(stored.getString("status")).isEqualTo("CONFIRMED");
        assertThat(stored.getString("confirmedPaymentCondition")).isEqualTo("COD_DUE");
        assertThat(stored.keySet()).doesNotContain("fulfillmentLocationId");
    }

    @Test void a_newer_edited_cart_is_preserved_and_the_marker_still_advances() {
        Fixture f = fixture(2);                       // live cart is v2 ...
        CheckoutQuoteId old = anotherQuote(f, 1);     // ... but this quote was taken from v1
        Document before = cart(f);

        Order o = service().placeCodOrder(f.customerId(), old.value());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        Document after = cart(f);
        assertThat(after.getList("items", Document.class)).isEqualTo(before.getList("items", Document.class)); // 12
        assertThat(after.get("version", Number.class).longValue()).isEqualTo(2);
        assertThat(after.getDate("updatedAt")).isEqualTo(before.getDate("updatedAt"));
        assertThat(marker(after)).isEqualTo(1);                                       // 13
        assertThat(onHand(LOC)).isEqualTo(8);
    }

    @Test void a_second_different_quote_from_the_same_cart_version_is_rejected_with_no_writes() {
        Fixture f = fixture(1);
        service().placeCodOrder(f.customerId(), f.quoteId().value());
        CheckoutQuoteId second = anotherQuote(f, 1);
        Document cartAfterFirst = cart(f);

        assertFailure(() -> service().placeCodOrder(f.customerId(), second.value()),
                OrderFailure.Reason.CART_VERSION_ALREADY_PURCHASED);                   // 14

        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("inventory_reservations")).isEqualTo(1);
        assertThat(onHand(LOC)).isEqualTo(8);                                          // no second consume
        assertThat(cart(f)).isEqualTo(cartAfterFirst);
        assertThat(counter("order_place_cod_failure", "reason", "cart_version_already_purchased")).isEqualTo(1);
    }

    @Test void an_older_unplaced_quote_is_rejected_once_a_newer_cart_version_was_purchased() {
        Fixture f = fixture(2);
        CheckoutQuoteId v1Quote = anotherQuote(f, 1);
        service().placeCodOrder(f.customerId(), f.quoteId().value());   // v2 purchased
        assertFailure(() -> service().placeCodOrder(f.customerId(), v1Quote.value()),
                OrderFailure.Reason.CART_VERSION_ALREADY_PURCHASED);     // 1 <= 2
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test void a_newer_version_quote_still_places_after_an_older_version_was_purchased() {
        Fixture f = fixture(2);
        CheckoutQuoteId v1Quote = anotherQuote(f, 1);
        service().placeCodOrder(f.customerId(), v1Quote.value());        // v1 purchased; v2 cart preserved
        Order o = service().placeCodOrder(f.customerId(), f.quoteId().value()); // quote from v2
        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(count("orders")).isEqualTo(2);
        assertThat(marker(cart(f))).isEqualTo(2);
        assertThat(onHand(LOC)).isEqualTo(6);
    }

    // ============================================================
    // 15-18: replay
    // ============================================================

    @Test void replay_returns_the_same_order_and_touches_nothing() {
        Fixture f = fixture(1);
        Order first = service().placeCodOrder(f.customerId(), f.quoteId().value());
        // the customer keeps shopping: a newer cart with new content must survive a replay untouched
        db.getCollection("customer_carts").updateOne(new Document("_id", f.customerId().value()),
                new Document("$set", new Document("version", 5L).append("items", List.of(
                        new Document("skuId", SKU).append("quantity", 1)
                                .append("addedAt", Date.from(NOW)).append("updatedAt", Date.from(NOW))))));
        Document cartBefore = cart(f);
        Document reservationBefore = reservationDoc(first.reservationId());

        Order replay = service().placeCodOrder(f.customerId(), f.quoteId().value());

        assertThat(replay).isEqualTo(first);                                          // 15
        assertThat(count("inventory_reservations")).isEqualTo(1);                     // 16: no second reserve
        assertThat(onHand(LOC)).isEqualTo(8);                                         // 17: no second consume
        assertThat(reserved(LOC)).isZero();
        assertThat(reservationDoc(first.reservationId())).isEqualTo(reservationBefore);
        assertThat(cart(f)).isEqualTo(cartBefore);                                    // 18: no second cart clear
        assertThat(count("orders")).isEqualTo(1);
        assertThat(counter("order_place_cod_success")).isEqualTo(2); // placed-or-replayed
    }

    @Test void replay_wins_even_though_the_source_cart_version_is_already_purchased_and_everything_else_changed() {
        Fixture f = fixture(1);
        Order first = service().placeCodOrder(f.customerId(), f.quoteId().value());
        assertThat(marker(cart(f))).isGreaterThanOrEqualTo(1); // the guard WOULD reject a fresh placement
        // quote expired, address edited, price changed, product gone, stock gone, route gone
        db.getCollection("checkout_quotes").updateOne(new Document("_id", f.quoteId().value()),
                new Document("$set", new Document("createdAt", Date.from(NOW.minusSeconds(300)))
                        .append("expiresAt", Date.from(NOW.minusSeconds(1)))));
        db.getCollection("customer_addresses").updateOne(new Document("_id", f.addressId()),
                new Document("$set", new Document("version", 9L)));
        db.getCollection("price_current").updateOne(new Document("sku_id", SKU),
                new Document("$set", new Document("selling_price_paise", 9999L)));
        db.getCollection("products").deleteMany(new Document());
        db.getCollection("inventory").deleteMany(new Document());
        db.getCollection("service_areas").deleteMany(new Document());

        assertThat(service().placeCodOrder(f.customerId(), f.quoteId().value())).isEqualTo(first);
    }

    @Test void a_replay_after_the_quote_document_is_deleted_still_returns_the_order() {
        Fixture f = fixture(1);
        Order first = service().placeCodOrder(f.customerId(), f.quoteId().value());
        db.getCollection("checkout_quotes").deleteMany(new Document());
        assertThat(service().placeCodOrder(f.customerId(), f.quoteId().value())).isEqualTo(first);
    }

    @Test void meeting_an_existing_CREATED_order_fails_closed_and_converts_nothing() {
        Fixture f = fixture(1);
        OrderService svc = service();
        Order created = svc.createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.confirmedAt()).isNull();
        assertThat(created.confirmedPaymentCondition()).isNull();
        Document cartBefore = cart(f);

        assertFailure(() -> svc.placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.INTEGRITY_FAILURE);

        Document stored = db.getCollection("orders").find(new Document("_id", created.orderId().value())).first();
        assertThat(stored.getString("status")).isEqualTo("CREATED");
        assertThat(reservationDoc(created.reservationId()).getString("status")).isEqualTo("RESERVED");
        assertThat(cart(f)).isEqualTo(cartBefore);
        assertThat(reserved(LOC)).isEqualTo(2);
    }

    @Test void the_internal_create_only_path_is_unchanged_CREATED_version_1_with_a_RESERVED_reservation() {
        Fixture f = fixture(1);
        Order created = service().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(created.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(reservationDoc(created.reservationId()).getString("status")).isEqualTo("RESERVED");
        assertThat(reserved(LOC)).isEqualTo(2);
        assertThat(onHand(LOC)).isEqualTo(10);
        assertThat(cart(f).get("version", Number.class).longValue()).isEqualTo(1); // create-only touches no cart
        assertThat(cart(f).containsKey("purchasedThroughVersion")).isFalse();
        Document stored = db.getCollection("orders").find(new Document("_id", created.orderId().value())).first();
        assertThat(stored.keySet()).doesNotContain("confirmedAt", "confirmedPaymentCondition");
    }

    // ============================================================
    // 19-20: concurrency (rendezvous INSIDE the transaction, after each contender's snapshot read)
    // ============================================================

    /** Each contender reads "no order yet" in ITS snapshot, then all wait for each other before writing. */
    private static final class RendezvousOrders extends OrderRepository {
        private final CountDownLatch barrier;
        private final Set<String> arrived = ConcurrentHashMap.newKeySet();

        RendezvousOrders(MongoDatabase db, int parties) {
            super(db);
            this.barrier = new CountDownLatch(parties);
        }

        @Override
        public Document findByCustomerAndQuote(ClientSession session, String customerId, String quoteId) {
            Document d = super.findByCustomerAndQuote(session, customerId, quoteId);
            if (arrived.add(customerId + "|" + quoteId + "|" + Thread.currentThread().getId())) {
                barrier.countDown(); // first attempt only: a retry on the same thread never waits again
                try {
                    if (!barrier.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("contenders did not rendezvous");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return d;
        }
    }

    private List<Object> race(OrderService svc, List<Callable<Order>> contenders) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(contenders.size());
        try {
            List<Future<Order>> futures = new ArrayList<>();
            for (Callable<Order> c : contenders) futures.add(pool.submit(c));
            List<Object> outcomes = new ArrayList<>();
            for (Future<Order> fut : futures) {
                try {
                    outcomes.add(fut.get(60, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void concurrent_placements_of_the_SAME_quote_resolve_to_one_order_and_one_consume() throws Exception {
        for (int round = 0; round < 3; round++) {
            reset();
            Fixture f = fixture(1);
            Tx tx = new Tx(client);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            OrderService svc = service(tx, clock, new RendezvousOrders(db, 3), reservationService(tx, clock, 600),
                    realCart(clock));

            List<Object> outcomes = race(svc, List.of(
                    () -> svc.placeCodOrder(f.customerId(), f.quoteId().value()),
                    () -> svc.placeCodOrder(f.customerId(), f.quoteId().value()),
                    () -> svc.placeCodOrder(f.customerId(), f.quoteId().value())));

            assertThat(outcomes).as("round %d: no contender failed", round).allSatisfy(o -> assertThat(o).isInstanceOf(Order.class));
            assertThat(outcomes.stream().map(o -> ((Order) o).orderId()).distinct()).hasSize(1); // the ONE winner's Order
            assertThat(count("orders")).isEqualTo(1);
            assertThat(count("inventory_reservations")).isEqualTo(1);                            // losers rolled back
            assertThat(onHand(LOC)).isEqualTo(8);                                                // at most ONE consume
            assertThat(reserved(LOC)).isZero();
            assertThat(cart(f).get("version", Number.class).longValue()).isEqualTo(2);           // cleared once
        }
    }

    @Test void concurrent_placements_of_DIFFERENT_quotes_from_one_cart_version_exactly_one_wins() throws Exception {
        for (int round = 0; round < 3; round++) {
            reset();
            Fixture f = fixture(1);
            CheckoutQuoteId q2 = anotherQuote(f, 1);
            CheckoutQuoteId q3 = anotherQuote(f, 1);
            Tx tx = new Tx(client);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            OrderService svc = service(tx, clock, new RendezvousOrders(db, 3), reservationService(tx, clock, 600),
                    realCart(clock));

            List<Object> outcomes = race(svc, List.of(
                    () -> svc.placeCodOrder(f.customerId(), f.quoteId().value()),
                    () -> svc.placeCodOrder(f.customerId(), q2.value()),
                    () -> svc.placeCodOrder(f.customerId(), q3.value())));

            long winners = outcomes.stream().filter(o -> o instanceof Order).count();
            assertThat(winners).as("round %d", round).isEqualTo(1);
            outcomes.stream().filter(o -> !(o instanceof Order)).forEach(o -> assertThat(o)
                    .isInstanceOf(OrderFailure.class)
                    .satisfies(e -> assertThat(((OrderFailure) e).reason())
                            .isEqualTo(OrderFailure.Reason.CART_VERSION_ALREADY_PURCHASED)));
            assertThat(count("orders")).isEqualTo(1);                 // loser never produced a second Order
            assertThat(count("inventory_reservations")).isEqualTo(1);
            assertThat(onHand(LOC)).isEqualTo(8);                     // loser never consumed a second time
            assertThat(reserved(LOC)).isZero();
            assertThat(marker(cart(f))).isGreaterThanOrEqualTo(1);
            assertThat(cart(f).get("version", Number.class).longValue()).isEqualTo(2);
        }
    }

    // ============================================================
    // 21-22: Inventory failures commit nothing
    // ============================================================

    @Test void reserve_failure_commits_nothing() {
        Fixture f = fixture(1);
        db.getCollection("inventory").updateOne(new Document("sku_id", SKU),
                new Document("$set", new Document("on_hand", 1L)));
        Document cartBefore = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.STOCK_UNAVAILABLE);
        assertThat(count("orders")).isZero();
        assertThat(count("inventory_reservations")).isZero();
        assertThat(reserved(LOC)).isZero();
        assertThat(cart(f)).isEqualTo(cartBefore);
    }

    @Test void consume_expiry_failure_commits_nothing() {
        Fixture f = fixture(1);
        MovableClock clock = new MovableClock();
        Tx tx = new Tx(client);
        InventoryReservationService expiringAfterReserve = new InventoryReservationService(
                new InventoryService(tx, new WritePath(db), clock), new InventoryReservationRepository(db), props(60),
                new InventoryReservationObservability(new SimpleMeterRegistry()), clock, tx) {
            @Override public InventoryReservation reserve(ClientSession s, PreparedInventoryReservation p,
                                                          InventoryReservationAllocation a) {
                InventoryReservation r = super.reserve(s, p, a);
                clock.now.set(NOW.plusSeconds(120)); // the hold lapses between reserve and consume
                return r;
            }
        };
        OrderService svc = service(tx, clock, new OrderRepository(db), expiringAfterReserve, realCart(clock));
        Document cartBefore = cart(f);
        assertFailure(() -> svc.placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.RESERVATION_EXPIRED);
        assertNothingCommitted(f, cartBefore);
    }

    // ============================================================
    // 23-24: expiry / release race
    // ============================================================

    @Test void a_release_that_committed_first_makes_the_placement_fail_with_nothing_committed() {
        Fixture f = fixture(1);
        Tx plainTx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService plain = reservationService(plainTx, clock, 600);
        AtomicReference<String> capturedOrderId = new AtomicReference<>();
        InventoryReservationService capturing = new InventoryReservationService(
                new InventoryService(plainTx, new WritePath(db), clock), new InventoryReservationRepository(db),
                props(600), new InventoryReservationObservability(new SimpleMeterRegistry()), clock, plainTx) {
            @Override public PreparedInventoryReservation prepare(String orderId) {
                capturedOrderId.set(orderId);
                return super.prepare(orderId);
            }
        };
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        // attempt 1 aborts; BEFORE attempt 2, a competing actor commits a reservation for this very order
        // and the release (what the expiry worker does) -- so the retry meets a durable RELEASED hold.
        retryTx.arm(1, n -> {
            if (n == 2) {
                InventoryReservation r = plain.reserve(capturedOrderId.get(), LOC,
                        List.of(new InventoryReservationItem(SKU, 2)));
                plain.release(new InventoryReservationId(r.reservationId()));
            }
        });
        OrderService svc = service(retryTx, clock, new OrderRepository(db), capturing, realCart(clock));
        Document cartBefore = cart(f);

        assertFailure(() -> svc.placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.RESERVATION_EXPIRED);

        assertThat(count("orders")).isZero();                                   // NO Order commit
        assertThat(cart(f)).isEqualTo(cartBefore);                              // NO cart/marker change
        assertThat(onHand(LOC)).isEqualTo(10);
        assertThat(reserved(LOC)).isZero();
        assertThat(db.getCollection("inventory_reservations").find().first().getString("status"))
                .isEqualTo("RELEASED");                                          // only the competitor's header
    }

    @Test void once_the_placement_committed_the_expiry_worker_and_an_explicit_release_cannot_release_it() {
        Fixture f = fixture(1);
        MovableClock clock = new MovableClock();
        Tx tx = new Tx(client);
        InventoryReservationService reservations = reservationService(tx, clock, 60);
        OrderService svc = service(tx, clock, new OrderRepository(db), reservations, realCart(clock));
        Order o = svc.placeCodOrder(f.customerId(), f.quoteId().value());

        clock.now.set(NOW.plusSeconds(3600)); // far past the hold's expiry
        InventoryReservationExpiryWorker worker = new InventoryReservationExpiryWorker(
                new InventoryReservationRepository(db), reservations,
                new InventoryReservationObservability(new SimpleMeterRegistry()), clock);
        assertThat(worker.reconcileExpired(100)).isZero();   // nothing RESERVED to expire

        assertThatThrownBy(() -> reservations.release(new InventoryReservationId(o.reservationId())))
                .isInstanceOf(InventoryReservationFailure.class)
                .satisfies(e -> assertThat(((InventoryReservationFailure) e).reason())
                        .isEqualTo(InventoryReservationFailure.Reason.INVALID_TRANSITION));
        assertThat(reservationDoc(o.reservationId()).getString("status")).isEqualTo("CONSUMED");
        assertThat(onHand(LOC)).isEqualTo(8);
        assertThat(db.getCollection("orders").find(new Document("_id", o.orderId().value())).first()
                .getString("status")).isEqualTo("CONFIRMED");
    }

    // ============================================================
    // 25-27: rollback at each step takes everything with it
    // ============================================================

    @Test void rollback_after_reserve_rolls_everything_back() {
        Fixture f = fixture(1);
        Tx tx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService failingConsume = new InventoryReservationService(
                new InventoryService(tx, new WritePath(db), clock), new InventoryReservationRepository(db),
                props(600), new InventoryReservationObservability(new SimpleMeterRegistry()), clock, tx) {
            @Override public InventoryReservation consume(ClientSession s, InventoryReservationId id) {
                throw new IllegalStateException("forced abort AFTER reserve, BEFORE consume");
            }
        };
        OrderService svc = service(tx, clock, new OrderRepository(db), failingConsume, realCart(clock));
        Document cartBefore = cart(f);
        assertThatThrownBy(() -> svc.placeCodOrder(f.customerId(), f.quoteId().value()))
                .hasMessageContaining("forced abort");
        assertNothingCommitted(f, cartBefore);
    }

    @Test void rollback_after_consume_rolls_everything_back() {
        Fixture f = fixture(1);
        Tx tx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CartPurchasePort failingCart = new CartPurchasePort() {
            @Override public boolean isSourceVersionPurchased(ClientSession s, CustomerId c, long v) {
                return realCart(clock).isSourceVersionPurchased(s, c, v);
            }
            @Override public CartPurchaseOutcome finalizePurchase(ClientSession s, CustomerId c, long v) {
                throw new IllegalStateException("forced abort AFTER consume, BEFORE cart finalization");
            }
        };
        OrderService svc = service(tx, clock, new OrderRepository(db), reservationService(tx, clock, 600), failingCart);
        Document cartBefore = cart(f);
        assertThatThrownBy(() -> svc.placeCodOrder(f.customerId(), f.quoteId().value()))
                .hasMessageContaining("forced abort");
        assertNothingCommitted(f, cartBefore); // the consume (on_hand decrement) rolled back too
    }

    @Test void rollback_after_cart_finalization_rolls_everything_back() {
        Fixture f = fixture(1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Tx abortingTx = new Tx(client) {
            @Override public <T> T call(Function<ClientSession, T> body) {
                return super.call(session -> {
                    body.apply(session); // reserve, consume, cart finalization AND the order insert all ran
                    throw new IllegalStateException("forced abort AFTER everything ran");
                });
            }
        };
        OrderService svc = service(abortingTx, clock, new OrderRepository(db),
                reservationService(abortingTx, clock, 600), realCart(clock));
        Document cartBefore = cart(f);
        assertThatThrownBy(() -> svc.placeCodOrder(f.customerId(), f.quoteId().value()))
                .hasMessageContaining("forced abort");
        assertNothingCommitted(f, cartBefore); // marker did NOT advance, cart NOT cleared
        assertThat(counter("order_place_cod_success")).isZero(); // never counted a rolled-back operation
    }

    // ============================================================
    // 28-29: forced driver retry
    // ============================================================

    @Test void a_forced_driver_retry_places_once_and_mutates_each_thing_once() {
        Fixture f = fixture(1);
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        retryTx.arm(1);
        OrderService svc = service(retryTx, clock, new OrderRepository(db), reservationService(retryTx, clock, 600),
                realCart(clock));

        Order o = svc.placeCodOrder(f.customerId(), f.quoteId().value());

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("inventory_reservations")).isEqualTo(1);
        assertThat(onHand(LOC)).isEqualTo(8);                                 // consumed once, not twice
        assertThat(reserved(LOC)).isZero();
        Document cart = cart(f);
        assertThat(cart.get("version", Number.class).longValue()).isEqualTo(2); // cleared once
        assertThat(marker(cart)).isEqualTo(1);
        assertThat(reservationDoc(o.reservationId()).getString("status")).isEqualTo("CONSUMED");
        assertThat(counter("order_place_cod_success")).isEqualTo(1);           // one success, not one per attempt
    }

    @Test void a_route_change_between_retry_attempts_rebuilds_the_allocation_from_the_latest_route() {
        Fixture f = fixture(1);
        seedStock("FL-ALT", 10);
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        retryTx.arm(1, n -> {
            if (n == 2) {
                db.getCollection("service_areas").updateOne(new Document("pincode", PIN), new Document("$set",
                        new Document("routes", List.of(new Document("fulfillment_location_id", "FL-ALT")
                                .append("priority", 1).append("active", true)))));
            }
        });
        OrderService svc = service(retryTx, clock, new OrderRepository(db), reservationService(retryTx, clock, 600),
                realCart(clock));

        Order o = svc.placeCodOrder(f.customerId(), f.quoteId().value());

        assertThat(reservationDoc(o.reservationId()).getString("fulfillmentLocationId")).isEqualTo("FL-ALT");
        assertThat(onHand("FL-ALT")).isEqualTo(8);   // the LATEST route consumed
        assertThat(onHand(LOC)).isEqualTo(10);       // the aborted attempt's route left no trace
        assertThat(reserved(LOC)).isZero();
        assertThat(reserved("FL-ALT")).isZero();
    }

    // ============================================================
    // 30-36: authority failures write nothing
    // ============================================================

    @Test void price_change_writes_nothing() {
        Fixture f = fixture(1);
        db.getCollection("price_current").updateOne(new Document("sku_id", SKU),
                new Document("$set", new Document("selling_price_paise", 5500L)));
        Document before = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()), OrderFailure.Reason.PRICE_CHANGED);
        assertNothingCommitted(f, before);
    }

    @Test void address_change_writes_nothing() {
        Fixture f = fixture(1);
        db.getCollection("customer_addresses").updateOne(new Document("_id", f.addressId()),
                new Document("$set", new Document("version", 2L)));
        Document before = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()), OrderFailure.Reason.ADDRESS_CHANGED);
        assertNothingCommitted(f, before);
    }

    @Test void product_unavailable_writes_nothing() {
        Fixture f = fixture(1);
        db.getCollection("products").deleteMany(new Document());
        Document before = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()), OrderFailure.Reason.PRODUCT_UNAVAILABLE);
        assertNothingCommitted(f, before);
    }

    @Test void not_serviceable_writes_nothing() {
        Fixture f = fixture(1);
        db.getCollection("service_areas").deleteMany(new Document());
        Document before = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()), OrderFailure.Reason.NOT_SERVICEABLE);
        assertNothingCommitted(f, before);
    }

    @Test void quote_expired_writes_nothing() {
        Fixture f = fixture(1);
        db.getCollection("checkout_quotes").updateOne(new Document("_id", f.quoteId().value()),
                new Document("$set", new Document("createdAt", Date.from(NOW.minusSeconds(300)))
                        .append("expiresAt", Date.from(NOW)))); // a valid quote that expires exactly now
        Document before = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()), OrderFailure.Reason.QUOTE_EXPIRED);
        assertNothingCommitted(f, before);
    }

    @Test void unknown_or_foreign_quote_is_QUOTE_NOT_FOUND() {
        Fixture f = fixture(1);
        assertFailure(() -> service().placeCodOrder(CustomerId.generate(), f.quoteId().value()), OrderFailure.Reason.QUOTE_NOT_FOUND);
        assertFailure(() -> service().placeCodOrder(f.customerId(), CheckoutQuoteId.generate().value()), OrderFailure.Reason.QUOTE_NOT_FOUND);
        assertFailure(() -> service().placeCodOrder(f.customerId(), "not-a-quote-id"), OrderFailure.Reason.QUOTE_NOT_FOUND);
    }

    @Test void a_marker_already_covering_the_source_version_is_a_conflict_with_no_writes() {
        Fixture f = fixture(3);
        db.getCollection("customer_carts").updateOne(new Document("_id", f.customerId().value()),
                new Document("$set", new Document("purchasedThroughVersion", 5L)));
        Document before = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.CART_VERSION_ALREADY_PURCHASED);
        assertNothingCommitted(f, before);
    }

    @Test void a_corrupt_cart_marker_is_an_integrity_failure_with_no_writes() {
        Fixture f = fixture(1);
        db.getCollection("customer_carts").updateOne(new Document("_id", f.customerId().value()),
                new Document("$set", new Document("purchasedThroughVersion", "oops")));
        Document before = cart(f);
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.INTEGRITY_FAILURE);
        assertNothingCommitted(f, before);
    }

    @Test void a_missing_cart_document_is_an_integrity_failure_and_rolls_the_placement_back() {
        Fixture f = fixture(1);
        db.getCollection("customer_carts").deleteMany(new Document()); // a quote always implies a cart
        assertFailure(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.INTEGRITY_FAILURE);
        assertThat(count("orders")).isZero();
        assertThat(count("inventory_reservations")).isZero(); // reserve + consume rolled back
        assertThat(onHand(LOC)).isEqualTo(10);
        assertThat(reserved(LOC)).isZero();
    }

    // ============================================================
    // 37-38: linkage corruption, outage
    // ============================================================

    @Test void corrupt_reservation_linkage_is_an_integrity_failure_and_everything_rolls_back() {
        Fixture f = fixture(1);
        Tx tx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InventoryReservationService wrongOrder = new InventoryReservationService(
                new InventoryService(tx, new WritePath(db), clock), new InventoryReservationRepository(db),
                props(600), new InventoryReservationObservability(new SimpleMeterRegistry()), clock, tx) {
            @Override public InventoryReservation consume(ClientSession s, InventoryReservationId id) {
                InventoryReservation r = super.consume(s, id); // the real consume ran ...
                return new InventoryReservation(r.reservationId(), "ORD_someoneElse-000000", // ... but claims another order
                        r.fulfillmentLocationId(), r.items(), r.status(), r.createdAt(), r.expiresAt(), r.updatedAt());
            }
        };
        OrderService svc = service(tx, clock, new OrderRepository(db), wrongOrder, realCart(clock));
        Document before = cart(f);
        assertFailure(() -> svc.placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.INTEGRITY_FAILURE);
        assertNothingCommitted(f, before); // including the consume that really ran
    }

    @Test void mongo_outage_maps_to_UNAVAILABLE() {
        MongoClient brokenClient = com.mongodb.client.MongoClients.create(
                "mongodb://127.0.0.1:1/?connectTimeoutMS=200&serverSelectionTimeoutMS=200");
        MongoDatabase brokenDb = brokenClient.getDatabase("tazzzo_it");
        Tx brokenTx = new Tx(brokenClient);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        WritePath writePath = new WritePath(brokenDb);
        OrderService svc = new OrderService(new OrderRepository(brokenDb), new CheckoutQuoteRepository(brokenDb),
                new AddressRepository(brokenDb), new ServiceabilityService(brokenTx, brokenDb,
                        new DomainAudit(brokenDb, clock), clock),
                new PricingService(brokenTx, writePath, clock), new com.tazzzo.commerce.read.CatalogCardReader(brokenDb),
                new InventoryReservationService(new InventoryService(brokenTx, writePath, clock),
                        new InventoryReservationRepository(brokenDb), new InventoryReservationProperties(),
                        new InventoryReservationObservability(new SimpleMeterRegistry()), clock, brokenTx),
                new CartPurchaseService(new CartRepository(brokenDb), clock), clock, ALWAYS_EXISTS, brokenTx,
                new OrderObservability(registry));
        assertFailure(() -> svc.placeCodOrder(CustomerId.generate(), CheckoutQuoteId.generate().value()),
                OrderFailure.Reason.UNAVAILABLE);
        assertThat(counter("order_place_cod_failure", "reason", "unavailable")).isEqualTo(1);
        brokenClient.close();
    }

    // ============================================================
    // 39-41: snapshots, strict schema, no speculative states
    // ============================================================

    @Test void money_and_title_brand_address_snapshots_survive_later_changes() {
        Fixture f = fixture(1);
        Order o = service().placeCodOrder(f.customerId(), f.quoteId().value());
        assertThat(o.subtotalPaise()).isEqualTo(10000);   // 2 x 5000, exact
        assertThat(o.itemCount()).isEqualTo(2);
        assertThat(o.lines().get(0).title()).isEqualTo("COD Widget");
        assertThat(o.lines().get(0).brandCode()).isEqualTo("BR-1");
        assertThat(o.addressSnapshot().addressLine1()).isEqualTo("123 Test Street");

        db.getCollection("price_current").updateOne(new Document("sku_id", SKU),
                new Document("$set", new Document("selling_price_paise", 7777L)));
        db.getCollection("products").updateOne(new Document("_id", SKU),
                new Document("$set", new Document("title", "Renamed").append("brand_code", "BR-2")));
        db.getCollection("customer_addresses").updateOne(new Document("_id", f.addressId()),
                new Document("$set", new Document("addressLine1", "Moved Street").append("version", 2L)));

        Document stored = db.getCollection("orders").find(new Document("_id", o.orderId().value())).first();
        assertThat(OrderRepository.toOrder(stored)).isEqualTo(o);
    }

    @Test void the_persisted_schema_is_strict_nothing_is_defaulted() {
        Fixture f = fixture(1);
        Order o = service().placeCodOrder(f.customerId(), f.quoteId().value());
        String id = o.orderId().value();
        for (String field : List.of("version", "paymentMethod")) {
            Document stored = db.getCollection("orders").find(new Document("_id", id)).first();
            stored.remove(field);
            assertThatThrownBy(() -> OrderRepository.toOrder(stored)).as("missing %s", field)
                    .isInstanceOf(RuntimeException.class);
        }
        for (String field : List.of("confirmedAt", "confirmedPaymentCondition")) {
            Document stored = db.getCollection("orders").find(new Document("_id", id)).first();
            stored.remove(field);
            assertThatThrownBy(() -> OrderRepository.toOrder(stored)).as("CONFIRMED missing %s", field)
                    .isInstanceOf(RuntimeException.class);
        }
        Document wrongVersion = db.getCollection("orders").find(new Document("_id", id)).first();
        wrongVersion.put("version", 1L);
        assertThatThrownBy(() -> OrderRepository.toOrder(wrongVersion)).isInstanceOf(IllegalArgumentException.class);

        // a replay against a row missing a required field fails LOUD -- it is never normalized to "version 1"
        db.getCollection("orders").updateOne(new Document("_id", id), new Document("$unset", new Document("version", "")));
        assertThatThrownBy(() -> service().placeCodOrder(f.customerId(), f.quoteId().value()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void only_the_reachable_states_and_conditions_exist() {
        assertThat(Arrays.stream(OrderStatus.values()).map(Enum::name)).containsExactly("CREATED", "CONFIRMED");
        assertThat(Arrays.stream(PaymentMethod.values()).map(Enum::name)).containsExactly("COD");
        assertThat(Arrays.stream(ConfirmedPaymentCondition.values()).map(Enum::name)).containsExactly("COD_DUE");
    }

    @Test void no_order_http_controller_and_no_payment_types_exist_in_this_pr() {
        for (String name : List.of("com.tazzzo.customer.order.OrderController",
                "com.tazzzo.customer.order.PaymentConditionAuthority", "com.tazzzo.customer.payment.PaymentService")) {
            assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
        }
    }

    // ============================================================
    // duplicate-key (11000) recovery: the proof is the durable row, never MongoDB's error text
    // ============================================================

    /**
     * TEST-ONLY. Reproduces a lost race: the contender's pre-transaction and in-transaction replay reads
     * both saw "no Order yet", and its insert then fails with a synthetic 11000 whose message is chosen by
     * the test. After the failure the reads see the real collection again (what recovery re-reads).
     */
    private static final class DuplicateKeyOnInsertOrders extends OrderRepository {
        private final String message;
        private volatile boolean hideExisting = true;

        DuplicateKeyOnInsertOrders(MongoDatabase db, String message) {
            super(db);
            this.message = message;
        }

        @Override public Document findByCustomerAndQuote(String customerId, String quoteId) {
            return hideExisting ? null : super.findByCustomerAndQuote(customerId, quoteId);
        }

        @Override public Document findByCustomerAndQuote(ClientSession session, String customerId, String quoteId) {
            return hideExisting ? null : super.findByCustomerAndQuote(session, customerId, quoteId);
        }

        @Override public void insert(ClientSession session, Order order) {
            hideExisting = false;
            throw new MongoWriteException(new WriteError(11000, message, new BsonDocument()), new ServerAddress());
        }
    }

    private OrderService contender(String duplicateMessage) {
        Tx tx = new Tx(client);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return service(tx, clock, new DuplicateKeyOnInsertOrders(db, duplicateMessage),
                reservationService(tx, clock, 600), realCart(clock));
    }

    /** After a winner committed, put cart and stock back to their pre-placement state, so the contender
     *  passes every guard and reaches its insert — and any side effect it leaves behind is detectable. */
    private void rewindCartAndStock(Fixture f) {
        db.getCollection("customer_carts").deleteMany(new Document());
        seedCart(f.customerId().value(), f.cartVersion());
        db.getCollection("inventory").updateOne(new Document("sku_id", SKU), new Document("$set",
                new Document("on_hand", 10L).append("reserved", 0L)));
    }

    @Test void a_same_quote_duplicate_key_race_returns_the_durable_winner_create_only() {
        Fixture f = fixture(1);
        Order winner = service().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD); // CREATED
        long reservedBefore = reserved(LOC);
        long reservationsBefore = count("inventory_reservations");

        Order result = contender("unrelated text, no index name").createOrder(f.customerId(), f.quoteId().value(),
                PaymentMethod.COD);

        assertThat(result).isEqualTo(winner);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("inventory_reservations")).isEqualTo(reservationsBefore); // contender rolled back
        assertThat(reserved(LOC)).isEqualTo(reservedBefore);
    }

    @Test void cod_duplicate_recovery_with_a_CONFIRMED_winner_replays_without_any_second_mutation() {
        Fixture f = fixture(1);
        Order winner = service().placeCodOrder(f.customerId(), f.quoteId().value());
        rewindCartAndStock(f);
        Document cartBefore = cart(f);
        long reservationsBefore = count("inventory_reservations");

        Order result = contender("").placeCodOrder(f.customerId(), f.quoteId().value());

        assertThat(result).isEqualTo(winner);
        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("inventory_reservations")).isEqualTo(reservationsBefore); // no second reserve
        assertThat(onHand(LOC)).isEqualTo(10);                                      // no second consume
        assertThat(reserved(LOC)).isZero();
        assertThat(cart(f)).isEqualTo(cartBefore);                                  // no second cart mutation
        assertThat(cart(f).containsKey("purchasedThroughVersion")).isFalse();       // marker not advanced by the loser
        assertThat(counter("order_place_cod_success")).isEqualTo(2);                // winner + recovered replay
    }

    @Test void cod_duplicate_recovery_with_a_CREATED_winner_fails_closed_and_converts_nothing() {
        Fixture f = fixture(1);
        Order created = service().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        Document cartBefore = cart(f);
        long reservedBefore = reserved(LOC);

        assertFailure(() -> contender("anything").placeCodOrder(f.customerId(), f.quoteId().value()),
                OrderFailure.Reason.INTEGRITY_FAILURE);

        Document stored = db.getCollection("orders").find(new Document("_id", created.orderId().value())).first();
        assertThat(stored.getString("status")).isEqualTo("CREATED");                // not silently converted
        assertThat(reservationDoc(created.reservationId()).getString("status")).isEqualTo("RESERVED");
        assertThat(count("orders")).isEqualTo(1);
        assertThat(reserved(LOC)).isEqualTo(reservedBefore);
        assertThat(cart(f)).isEqualTo(cartBefore);
    }

    @Test void an_unrelated_duplicate_key_with_no_same_quote_order_is_an_integrity_failure() {
        Fixture f = fixture(1);
        Document cartBefore = cart(f);

        assertFailure(() -> contender("E11000 duplicate key error ... some other unique index")
                .placeCodOrder(f.customerId(), f.quoteId().value()), OrderFailure.Reason.INTEGRITY_FAILURE);

        assertNothingCommitted(f, cartBefore); // the aborted transaction left nothing behind
        assertThat(counter("order_place_cod_failure", "reason", "integrity_failure")).isEqualTo(1);
    }

    @Test void duplicate_recovery_never_depends_on_the_mongo_message_or_index_name() {
        // the message that USED to be the discriminator, with NO same-quote Order: still an integrity
        // failure -- the error text cannot turn an unrelated duplicate into a replay.
        Fixture f = fixture(1);
        Document cartBefore = cart(f);
        assertFailure(() -> contender("index: order_one_per_quote dup key")
                .placeCodOrder(f.customerId(), f.quoteId().value()), OrderFailure.Reason.INTEGRITY_FAILURE);
        assertNothingCommitted(f, cartBefore);

        // ... and with a same-quote Order present, ANY message (empty, unrelated, or naming another index)
        // recovers the durable winner identically.
        Fixture g = fixture2(f);
        Order winner = service().placeCodOrder(g.customerId(), g.quoteId().value());
        rewindCartAndStock(g);
        for (String message : List.of("", "totally unrelated", "index: inventory_reservation_one_per_order dup key")) {
            assertThat(contender(message).placeCodOrder(g.customerId(), g.quoteId().value())).isEqualTo(winner);
        }
        assertThat(count("orders")).isEqualTo(1);
    }

    /** A second, independent customer/quote on the same seeded world (price/product/stock/route are shared). */
    private Fixture fixture2(Fixture base) {
        CustomerId customerId = CustomerId.generate();
        String addressId = AddressId.generate().value();
        seedAddress(addressId, customerId.value(), 1L, "123 Test Street");
        seedCart(customerId.value(), 1);
        CheckoutQuoteId quoteId = CheckoutQuoteId.generate();
        insertQuote(quoteId.value(), customerId, addressId, 1, NOW.plusSeconds(600));
        return new Fixture(customerId, addressId, quoteId, 1);
    }

    @Test void order_service_source_does_not_parse_mongo_error_text() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java/com/tazzzo/customer/order/OrderService.java");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(src));
        String code = java.nio.file.Files.readString(src);
        assertThat(code).doesNotContain("order_one_per_quote");
        assertThat(code).doesNotContain("getMessage().contains");
        assertThat(code).doesNotContain(".getMessage()");
    }

    // ============================================================
    // metrics
    // ============================================================

    @Test void metrics_count_placements_and_failures_once_with_bounded_tags() {
        Fixture f = fixture(1);
        OrderService svc = service();
        svc.placeCodOrder(f.customerId(), f.quoteId().value());
        assertThat(counter("order_place_cod_success")).isEqualTo(1);
        assertFailure(() -> svc.placeCodOrder(f.customerId(), CheckoutQuoteId.generate().value()),
                OrderFailure.Reason.QUOTE_NOT_FOUND);
        assertThat(counter("order_place_cod_failure", "reason", "quote_not_found")).isEqualTo(1);
        assertThat(counter("order_create_success")).isZero(); // create-only metrics untouched by COD
        registry.getMeters().forEach(m -> m.getId().getTags().forEach(t ->
                assertThat(t.getKey()).isIn("reason"))); // closed-enum tags only
    }
}
