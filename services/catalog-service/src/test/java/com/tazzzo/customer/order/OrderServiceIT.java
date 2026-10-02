package com.tazzzo.customer.order;

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
import com.tazzzo.customer.checkout.CheckoutQuote;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.customer.checkout.CheckoutQuoteRepository;
import com.tazzzo.inventory.InventoryReservationObservability;
import com.tazzzo.inventory.InventoryReservationProperties;
import com.tazzzo.inventory.InventoryReservationRepository;
import com.tazzzo.inventory.InventoryReservationService;
import com.tazzzo.inventory.InventoryReservationStatus;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.serviceability.ServiceabilityService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-14B — {@link OrderService} against a real Mongo transaction (Testcontainers): the durable
 * replay fast-path/idempotency authority, the in-transaction authoritative validation order, and
 * every mutable-state TOCTOU the design closed. No sleeps: retries use {@link RetryInjectingTx};
 * clock movement uses a movable {@link Clock}; concurrency uses direct DB mutation between steps.
 */
@SpringBootTest(classes = CatalogApplication.class)
class OrderServiceIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final String PIN = "560001";
    private static final String LOC = "FL-ORD-1";

    // {@code client}/{@code db} are inherited protected fields from AbstractMongoIT.

    @BeforeEach
    void resetOrderCollections() {
        db.getCollection("orders").deleteMany(new Document());
        db.getCollection("checkout_quotes").deleteMany(new Document());
        db.getCollection("customer_addresses").deleteMany(new Document());
        db.getCollection("inventory_reservations").deleteMany(new Document());
        db.getCollection("inventory").deleteMany(new Document());
        db.getCollection("price_current").deleteMany(new Document());
        db.getCollection("service_areas").deleteMany(new Document());
        db.getCollection("products").deleteMany(new Document());
    }

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

    private OrderService orderService(Tx tx, Clock clock) {
        WritePath writePath = new WritePath(db);
        PricingService pricing = new PricingService(tx, writePath, clock);
        ServiceabilityService serviceability = new ServiceabilityService(tx, db, new DomainAudit(db, clock), clock);
        com.tazzzo.commerce.read.CatalogCardReader catalog = new com.tazzzo.commerce.read.CatalogCardReader(db);
        InventoryService inventory = new InventoryService(tx, writePath, clock);
        InventoryReservationProperties props = new InventoryReservationProperties();
        props.setTtlSeconds(600);
        props.setExpiryBatchSize(100);
        InventoryReservationService reservations = new InventoryReservationService(inventory,
                new InventoryReservationRepository(db), props,
                new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                clock, tx);
        return new OrderService(new OrderRepository(db), new CheckoutQuoteRepository(db), new AddressRepository(db),
                serviceability, pricing, catalog, TestBenefits.NO_MEMBERSHIP, reservations,
                new com.tazzzo.customer.cart.CartPurchaseService(new com.tazzzo.customer.cart.CartRepository(db), clock),
                clock, ALWAYS_EXISTS, tx,
                new OrderObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private OrderService orderService() {
        return orderService(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------- seed helpers ----------

    private void seedAddress(String addressId, String customerId, long version, String postalCode) {
        db.getCollection("customer_addresses").insertOne(new Document("_id", addressId)
                .append("customerId", customerId).append("label", "HOME").append("recipientName", "Test Recipient")
                .append("recipientPhone", "9999999999").append("addressLine1", "123 Test Street")
                .append("addressLine2", "Near Landmark").append("landmark", "Landmark")
                .append("city", "Bengaluru").append("state", "Karnataka").append("postalCode", postalCode)
                .append("latitude", 12.9716).append("longitude", 77.5946).append("version", version)
                .append("createdAt", Date.from(NOW)).append("updatedAt", Date.from(NOW)));
    }

    private void seedServiceArea(String pin, String areaId, String fulfillmentLocationId) {
        db.getCollection("service_areas").insertOne(new Document("pincode", pin)
                .append("service_area_id", areaId).append("active", true)
                .append("routes", List.of(new Document("fulfillment_location_id", fulfillmentLocationId)
                        .append("priority", 1).append("active", true)))
                .append("version", 1L).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
    }

    private void seedPrice(String sku, long sellingPaise, long mrpPaise) {
        db.getCollection("price_current").insertOne(new Document("sku_id", sku)
                .append("currency", "INR").append("selling_price_paise", sellingPaise).append("mrp_paise", mrpPaise)
                .append("version", 1L).append("active", true).append("effective_from", null)
                .append("effective_to", null).append("source", "seed")
                .append("created_at", Date.from(NOW)).append("updated_at", Date.from(NOW)));
    }

    private void seedProduct(String sku, String title, String brandCode) {
        Document classification = new Document("vertical_id", "V-1").append("release_id", "R1")
                .append("status", "confirmed");
        db.getCollection("products").insertOne(new Document("_id", sku).append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", sku))
                .append("brand_code", brandCode).append("title", title)
                .append("lifecycle", "active").append("classification", classification)
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", new Date()));
    }

    private void seedStock(String sku, String loc, long onHand) {
        db.getCollection("inventory").insertOne(new Document("sku_id", sku)
                .append("fulfillment_location_id", loc).append("on_hand", onHand).append("reserved", 0L)
                .append("low_stock_threshold", 1L).append("max_purchasable", 100L).append("version", 1L)
                .append("active", true).append("source", "seed").append("created_at", new Date())
                .append("updated_at", new Date()));
    }

    private CheckoutQuote quote(String quoteId, String addressId, long addressVersion, String sku, int qty,
                                long unitPricePaise, Instant createdAt, Instant expiresAt) {
        long lineTotal = unitPricePaise * qty;
        return TestQuotes.bindNoBenefit(new CheckoutQuote(quoteId, 1L, addressId, addressVersion,
                List.of(new CheckoutQuote.Line(sku, qty, unitPricePaise, lineTotal)), qty, lineTotal, "INR",
                createdAt, expiresAt));
    }

    private void insertQuote(CheckoutQuote quote, String customerId) {
        Tx tx = new Tx(client);
        tx.run(session -> new CheckoutQuoteRepository(db).insert(session, quote, customerId,
                "digest-" + quote.quoteId(), "fingerprint-" + quote.quoteId()));
    }

    /** Full standard fixture: one customer, one address, one serviceable route, one priced/eligible
     *  SKU with stock, and one unexpired quote referencing all of it. */
    private record Fixture(CustomerId customerId, String addressId, CheckoutQuoteId quoteId, String sku) { }

    private Fixture standardFixture(Instant expiresAt) {
        CustomerId customerId = CustomerId.generate();
        String addressId = AddressId.generate().value();
        String sku = "TZP-ORD1";
        seedAddress(addressId, customerId.value(), 1L, PIN);
        seedServiceArea(PIN, "SA-1", LOC);
        seedPrice(sku, 5000, 6000);
        seedProduct(sku, "Order Widget", "BR-1");
        seedStock(sku, LOC, 10);
        CheckoutQuoteId quoteId = CheckoutQuoteId.generate();
        CheckoutQuote q = quote(quoteId.value(), addressId, 1L, sku, 2, 5000, NOW, expiresAt);
        insertQuote(q, customerId.value());
        return new Fixture(customerId, addressId, quoteId, sku);
    }

    private Fixture standardFixture() {
        return standardFixture(NOW.plusSeconds(600));
    }

    // ============================================================
    // 1-3: valid creation, reservation state, structural uniqueness
    // ============================================================

    @Test void a_valid_fresh_quote_creates_an_order_in_CREATED_status() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.lines()).hasSize(1);
        assertThat(order.lines().get(0).title()).isEqualTo("Order Widget");
        assertThat(order.lines().get(0).brandCode()).isEqualTo("BR-1");
    }

    @Test void the_paired_inventory_reservation_is_RESERVED_after_creation() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        Document reservation = db.getCollection("inventory_reservations")
                .find(new Document("_id", order.reservationId())).first();
        assertThat(reservation).isNotNull();
        assertThat(reservation.getString("status")).isEqualTo(InventoryReservationStatus.RESERVED.name());
        assertThat(reservation.getString("orderId")).isEqualTo(order.orderId().value());
    }

    @Test void the_unique_customerId_quoteId_index_structurally_forbids_a_second_order_for_one_quote() {
        Fixture f = standardFixture();
        orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD); // inserts itself via its own tx
        // a raw second insert attempt with the SAME (customerId, quoteId) but a different _id must
        // violate the unique index -- proving the structural guarantee, not merely a service-level check.
        assertThatThrownBy(() -> db.getCollection("orders").insertOne(new Document("_id", "ORD_second")
                .append("customerId", f.customerId().value()).append("quoteId", f.quoteId().value())
                .append("status", "CREATED").append("addressId", f.addressId()).append("addressVersion", 1L)
                .append("addressSnapshot", new Document()).append("lines", List.of())
                .append("itemCount", 1).append("subtotalPaise", 1L).append("currency", "INR")
                .append("reservationId", "RESV_x").append("createdAt", Date.from(NOW))
                .append("updatedAt", Date.from(NOW))))
                .isInstanceOf(com.mongodb.MongoWriteException.class);
    }

    @Test void a_replay_of_the_same_quote_returns_the_same_order() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        Order second = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(db.getCollection("orders").countDocuments()).isEqualTo(1);
    }

    // ============================================================
    // 5-6: fast-path never touches quote storage; existing order wins over ALL later mutations
    // ============================================================

    @Test void existing_order_fast_path_never_touches_checkout_quote_storage() {
        Fixture f = standardFixture();
        orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("checkout_quotes").deleteMany(new Document()); // remove the quote entirely
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.quoteId()).isEqualTo(f.quoteId().value());
    }

    @Test void existing_order_returned_after_quote_deleted() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("checkout_quotes").deleteMany(new Document());
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_after_quote_corrupted() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("checkout_quotes").updateOne(new Document("_id", f.quoteId().value()),
                new Document("$unset", new Document("addressVersion", "")));
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_after_quote_expired() {
        Fixture f = standardFixture(NOW.plusSeconds(5));
        Order first = orderService(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC))
                .createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        Order replay = orderService(new Tx(client), Clock.fixed(NOW.plusSeconds(1000), ZoneOffset.UTC))
                .createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_after_address_changed() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("customer_addresses").deleteMany(new Document());
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_after_serviceability_changed() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("service_areas").deleteMany(new Document());
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_after_price_changed() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("price_current").updateOne(new Document("sku_id", f.sku()),
                new Document("$set", new Document("selling_price_paise", 5500L)));
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_after_product_ineligible() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("products").deleteMany(new Document());
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_after_stock_changed() {
        Fixture f = standardFixture();
        Order first = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("inventory").updateOne(new Document("sku_id", f.sku()).append("fulfillment_location_id", LOC),
                new Document("$set", new Document("on_hand", 0L)));
        Order replay = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    @Test void existing_order_returned_even_when_everything_mutates_simultaneously() {
        Fixture f = standardFixture(NOW.plusSeconds(5));
        Order first = orderService(new Tx(client), Clock.fixed(NOW, ZoneOffset.UTC))
                .createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("customer_addresses").deleteMany(new Document());
        db.getCollection("service_areas").deleteMany(new Document());
        db.getCollection("price_current").deleteMany(new Document());
        db.getCollection("products").deleteMany(new Document());
        db.getCollection("inventory").updateOne(new Document("sku_id", f.sku()).append("fulfillment_location_id", LOC),
                new Document("$set", new Document("on_hand", 0L)));
        Order replay = orderService(new Tx(client), Clock.fixed(NOW.plusSeconds(1000), ZoneOffset.UTC))
                .createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
    }

    // ============================================================
    // quote-not-found / quote-expired
    // ============================================================

    @Test void unknown_foreign_quote_is_QUOTE_NOT_FOUND() {
        assertThatThrownBy(() -> orderService().createOrder(CustomerId.generate(), CheckoutQuoteId.generate().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.QUOTE_NOT_FOUND));
    }

    @Test void expired_quote_with_no_existing_order_is_QUOTE_EXPIRED() {
        Fixture f = standardFixture(NOW.plusSeconds(5));
        assertThatThrownBy(() -> orderService(new Tx(client), Clock.fixed(NOW.plusSeconds(1000), ZoneOffset.UTC))
                .createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.QUOTE_EXPIRED));
    }

    @Test void forced_retry_crossing_quote_expiry_commits_no_order_and_no_reservation() {
        Fixture f = standardFixture(NOW.plusSeconds(5));
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        java.util.concurrent.atomic.AtomicReference<Clock> movable =
                new java.util.concurrent.atomic.AtomicReference<>(Clock.fixed(NOW, ZoneOffset.UTC));
        Clock indirection = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return movable.get().instant(); }
        };
        retryTx.arm(1, n -> {
            if (n == 2) {
                movable.set(Clock.fixed(NOW.plusSeconds(1000), ZoneOffset.UTC)); // cross expiry before attempt 2
            }
        });
        OrderService svc = orderService(retryTx, indirection);
        assertThatThrownBy(() -> svc.createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.QUOTE_EXPIRED));
        assertThat(db.getCollection("orders").countDocuments()).isZero();
        assertThat(db.getCollection("inventory_reservations").countDocuments()).isZero();
    }

    // ============================================================
    // address
    // ============================================================

    @Test void address_removed_is_ADDRESS_CHANGED() {
        Fixture f = standardFixture();
        db.getCollection("customer_addresses").deleteMany(new Document());
        assertThatThrownBy(() -> orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.ADDRESS_CHANGED));
    }

    @Test void address_version_changed_is_ADDRESS_CHANGED() {
        Fixture f = standardFixture();
        db.getCollection("customer_addresses").updateOne(new Document("_id", f.addressId()),
                new Document("$set", new Document("version", 2L)));
        assertThatThrownBy(() -> orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.ADDRESS_CHANGED));
    }

    @Test void immutable_address_textual_and_coordinate_snapshot_survives_a_later_edit() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("customer_addresses").updateOne(new Document("_id", f.addressId()),
                new Document("$set", new Document("city", "Mumbai").append("latitude", 19.0760)
                        .append("longitude", 72.8777).append("version", 2L)));
        Document stored = db.getCollection("orders").find(new Document("_id", order.orderId().value())).first();
        Document snapshot = stored.get("addressSnapshot", Document.class);
        assertThat(snapshot.getString("city")).isEqualTo("Bengaluru");
        assertThat(snapshot.get("latitude", Number.class).doubleValue()).isEqualTo(12.9716);
        assertThat(snapshot.get("longitude", Number.class).doubleValue()).isEqualTo(77.5946);
    }

    // ============================================================
    // serviceability
    // ============================================================

    @Test void unserviceable_current_pin_is_NOT_SERVICEABLE() {
        Fixture f = standardFixture();
        db.getCollection("service_areas").deleteMany(new Document());
        assertThatThrownBy(() -> orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.NOT_SERVICEABLE));
    }

    @Test void route_changing_between_retry_attempts_uses_the_latest_route_reservationId_and_expiresAt_stable() {
        Fixture f = standardFixture();
        seedStock(f.sku(), "FL-ALT", 10);
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(1, n -> {
            if (n == 2) {
                db.getCollection("service_areas").updateOne(new Document("pincode", PIN), new Document("$set",
                        new Document("routes", List.of(new Document("fulfillment_location_id", "FL-ALT")
                                .append("priority", 1).append("active", true)))));
            }
        });
        OrderService svc = orderService(retryTx, Clock.fixed(NOW, ZoneOffset.UTC));
        Order order = svc.createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(retryTx.attempts()).isEqualTo(2);
        Document reservation = db.getCollection("inventory_reservations")
                .find(new Document("_id", order.reservationId())).first();
        assertThat(reservation.getString("fulfillmentLocationId")).as("committed attempt used the LATEST route")
                .isEqualTo("FL-ALT");
    }

    // ============================================================
    // pricing
    // ============================================================

    @Test void price_changed_is_PRICE_CHANGED() {
        Fixture f = standardFixture();
        db.getCollection("price_current").updateOne(new Document("sku_id", f.sku()),
                new Document("$set", new Document("selling_price_paise", 5500L)));
        assertThatThrownBy(() -> orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.PRICE_CHANGED));
    }

    @Test void price_missing_or_inactive_is_PRICE_CHANGED() {
        Fixture f = standardFixture();
        db.getCollection("price_current").deleteMany(new Document());
        assertThatThrownBy(() -> orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.PRICE_CHANGED));
    }

    @Test void one_of_many_skus_price_changed_rejects_the_whole_order() {
        CustomerId customerId = CustomerId.generate();
        String addressId = AddressId.generate().value();
        seedAddress(addressId, customerId.value(), 1L, PIN);
        seedServiceArea(PIN, "SA-1", LOC);
        String skuA = "TZP-MULTI1";
        String skuB = "TZP-MULTI2";
        seedPrice(skuA, 1000, 1200);
        seedPrice(skuB, 2000, 2400);
        seedProduct(skuA, "Item A", "BR-A");
        seedProduct(skuB, "Item B", "BR-B");
        seedStock(skuA, LOC, 10);
        seedStock(skuB, LOC, 10);
        CheckoutQuoteId quoteId = CheckoutQuoteId.generate();
        CheckoutQuote q = TestQuotes.bindNoBenefit(new CheckoutQuote(quoteId.value(), 1L, addressId, 1L,
                List.of(new CheckoutQuote.Line(skuA, 1, 1000, 1000), new CheckoutQuote.Line(skuB, 1, 2000, 2000)),
                2, 3000, "INR", NOW, NOW.plusSeconds(600)));
        insertQuote(q, customerId.value());
        db.getCollection("price_current").updateOne(new Document("sku_id", skuB),
                new Document("$set", new Document("selling_price_paise", 2200L)));
        assertThatThrownBy(() -> orderService().createOrder(customerId, quoteId.value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.PRICE_CHANGED));
        assertThat(db.getCollection("orders").countDocuments()).isZero();
    }

    // ============================================================
    // catalog eligibility / title snapshot
    // ============================================================

    @Test void product_ineligible_is_PRODUCT_UNAVAILABLE() {
        Fixture f = standardFixture();
        db.getCollection("products").deleteMany(new Document());
        assertThatThrownBy(() -> orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.PRODUCT_UNAVAILABLE));
    }

    @Test void title_updated_before_the_transaction_is_the_transactional_title_snapshotted() {
        Fixture f = standardFixture();
        db.getCollection("products").updateOne(new Document("_id", f.sku()),
                new Document("$set", new Document("title", "Renamed Before Order")));
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(order.lines().get(0).title()).isEqualTo("Renamed Before Order");
    }

    @Test void product_renamed_after_order_commit_does_not_change_the_stored_title() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("products").updateOne(new Document("_id", f.sku()),
                new Document("$set", new Document("title", "Renamed After Order")));
        Document stored = db.getCollection("orders").find(new Document("_id", order.orderId().value())).first();
        List<Document> lines = stored.getList("lines", Document.class);
        assertThat(lines.get(0).getString("title")).isEqualTo("Order Widget");
    }

    // ============================================================
    // inventory / reservation
    // ============================================================

    @Test void insufficient_stock_is_STOCK_UNAVAILABLE() {
        Fixture f = standardFixture();
        db.getCollection("inventory").updateOne(new Document("sku_id", f.sku()).append("fulfillment_location_id", LOC),
                new Document("$set", new Document("on_hand", 0L)));
        assertThatThrownBy(() -> orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.STOCK_UNAVAILABLE));
        assertThat(db.getCollection("orders").countDocuments()).isZero();
    }

    @Test void reservation_expiring_mid_retry_is_RESERVATION_EXPIRED_and_commits_nothing() {
        Fixture f = standardFixture();
        InventoryReservationProperties shortTtl = new InventoryReservationProperties();
        shortTtl.setTtlSeconds(60);
        shortTtl.setExpiryBatchSize(100);
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        AtomicReference<Instant> liveNow = new AtomicReference<>(NOW);
        Clock movable = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return liveNow.get(); }
        };
        WritePath writePath = new WritePath(db);
        PricingService pricing = new PricingService(retryTx, writePath, movable);
        ServiceabilityService serviceability = new ServiceabilityService(retryTx, db, new DomainAudit(db, movable), movable);
        com.tazzzo.commerce.read.CatalogCardReader catalog = new com.tazzzo.commerce.read.CatalogCardReader(db);
        InventoryService inventory = new InventoryService(retryTx, writePath, movable);
        InventoryReservationService reservations = new InventoryReservationService(inventory,
                new InventoryReservationRepository(db), shortTtl,
                new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                movable, retryTx);
        OrderService svc = new OrderService(new OrderRepository(db), new CheckoutQuoteRepository(db),
                new AddressRepository(db), serviceability, pricing, catalog, TestBenefits.NO_MEMBERSHIP, reservations,
                new com.tazzzo.customer.cart.CartPurchaseService(new com.tazzzo.customer.cart.CartRepository(db), movable),
                movable, ALWAYS_EXISTS,
                retryTx, new OrderObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        retryTx.arm(1, n -> {
            if (n == 2) {
                liveNow.set(NOW.plusSeconds(120)); // advance past the 60s TTL before attempt 2
            }
        });
        assertThatThrownBy(() -> svc.createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.RESERVATION_EXPIRED));
        assertThat(db.getCollection("orders").countDocuments()).isZero();
        assertThat(db.getCollection("inventory_reservations").countDocuments()).isZero();
    }

    @Test void order_insert_failure_rolls_back_the_inventory_reservation_together() {
        // simulate an order-insert failure via a duplicate _id collision inserted directly first,
        // forcing the SAME transaction's order insert to fail after the reservation already applied
        // its increments -- MongoDB must roll back BOTH together.
        Fixture f = standardFixture();
        Order preExisting = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        // seed a SECOND quote whose freshly-generated OrderId happens to collide is impractical
        // (CSPRNG); instead verify the ratified guarantee directly: a forced outer-transaction abort
        // after reserve() leaves neither reservation nor order behind (the transactional-atomicity
        // proof), using a fresh independent fixture.
        CustomerId c2 = CustomerId.generate();
        String addr2 = AddressId.generate().value();
        seedAddress(addr2, c2.value(), 1L, PIN);
        String sku2 = "TZP-ATOMIC";
        seedPrice(sku2, 100, 120);
        seedProduct(sku2, "Atomic Widget", "BR-X");
        seedStock(sku2, LOC, 10);
        CheckoutQuoteId q2 = CheckoutQuoteId.generate();
        insertQuote(quote(q2.value(), addr2, 1L, sku2, 1, 100, NOW, NOW.plusSeconds(600)), c2.value());

        Tx abortingTx = new Tx(client) {
            @Override public <T> T call(java.util.function.Function<ClientSession, T> body) {
                return super.call(session -> {
                    T result = body.apply(session);
                    throw new RuntimeException("forced abort AFTER reserve+insert both ran");
                });
            }
        };
        OrderService svc = orderService(abortingTx, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> svc.createOrder(c2, q2.value(), PaymentMethod.COD)).hasMessageContaining("forced abort");
        assertThat(db.getCollection("orders").find(new Document("quoteId", q2.value())).first()).isNull();
        assertThat(db.getCollection("inventory").find(new Document("sku_id", sku2)).first()
                .get("reserved", Number.class).longValue()).isZero();
    }

    @Test void forced_outer_retry_produces_no_duplicate_order_or_reservation() {
        Fixture f = standardFixture();
        RetryInjectingTx retryTx = new RetryInjectingTx(client);
        retryTx.arm(1);
        OrderService svc = orderService(retryTx, Clock.fixed(NOW, ZoneOffset.UTC));
        Order order = svc.createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(db.getCollection("orders").countDocuments()).isEqualTo(1);
        assertThat(db.getCollection("inventory_reservations").countDocuments()).isEqualTo(1);
        assertThat(db.getCollection("inventory").find(new Document("sku_id", f.sku())).first()
                .get("reserved", Number.class).longValue()).isEqualTo(2);
    }

    // ============================================================
    // shape / provenance / money
    // ============================================================

    @Test void order_carries_no_public_fulfillmentLocationId_field() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        Document stored = db.getCollection("orders").find(new Document("_id", order.orderId().value())).first();
        assertThat(stored.keySet()).doesNotContain("fulfillmentLocationId");
    }

    @Test void reservationId_is_the_only_routing_provenance_on_the_order() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(order.reservationId()).isNotBlank();
        Document reservation = db.getCollection("inventory_reservations")
                .find(new Document("_id", order.reservationId())).first();
        assertThat(reservation.getString("fulfillmentLocationId")).isEqualTo(LOC);
    }

    @Test void money_subtotal_and_itemCount_are_exact() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        assertThat(order.subtotalPaise()).isEqualTo(10000L); // 2 * 5000
        assertThat(order.itemCount()).isEqualTo(2);
    }

    @Test void a_corrupt_persisted_order_fails_loud_on_reconstruction() {
        Fixture f = standardFixture();
        Order order = orderService().createOrder(f.customerId(), f.quoteId().value(), PaymentMethod.COD);
        db.getCollection("orders").updateOne(new Document("_id", order.orderId().value()),
                new Document("$unset", new Document("reservationId", "")));
        Document corrupt = db.getCollection("orders").find(new Document("_id", order.orderId().value())).first();
        assertThatThrownBy(() -> OrderRepository.toOrder(corrupt)).isInstanceOf(RuntimeException.class);
    }

    @Test void mongo_outage_maps_to_UNAVAILABLE() {
        // an entirely unreachable datastore: every repository/service this OrderService depends on
        // is wired against the broken client/database (a ClientSession is tied to the MongoClient it
        // came from, so a real outage-in-the-transaction scenario would ALSO fail at the same
        // unreachable host) -- exercising createOrder's outer catch(MongoException) -> UNAVAILABLE.
        MongoClient brokenClient = com.mongodb.client.MongoClients.create(
                "mongodb://127.0.0.1:1/?connectTimeoutMS=200&serverSelectionTimeoutMS=200");
        MongoDatabase brokenDb = brokenClient.getDatabase("tazzzo_it");
        Tx brokenTx = new Tx(brokenClient);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        WritePath writePath = new WritePath(brokenDb);
        OrderService svc = new OrderService(new OrderRepository(brokenDb), new CheckoutQuoteRepository(brokenDb),
                new AddressRepository(brokenDb), new ServiceabilityService(brokenTx, brokenDb,
                        new DomainAudit(brokenDb, clock), clock),
                new PricingService(brokenTx, writePath, clock), new com.tazzzo.commerce.read.CatalogCardReader(brokenDb), TestBenefits.NO_MEMBERSHIP,
                new InventoryReservationService(new InventoryService(brokenTx, writePath, clock),
                        new InventoryReservationRepository(brokenDb), new InventoryReservationProperties(),
                        new InventoryReservationObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                        clock, brokenTx),
                new com.tazzzo.customer.cart.CartPurchaseService(new com.tazzzo.customer.cart.CartRepository(brokenDb), clock),
                clock, ALWAYS_EXISTS, brokenTx,
                new OrderObservability(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        assertThatThrownBy(() -> svc.createOrder(CustomerId.generate(), CheckoutQuoteId.generate().value(), PaymentMethod.COD))
                .isInstanceOf(OrderFailure.class)
                .satisfies(e -> assertThat(((OrderFailure) e).reason()).isEqualTo(OrderFailure.Reason.UNAVAILABLE));
        brokenClient.close();
    }

    @Test void the_internal_create_only_path_is_not_public_so_no_http_layer_can_reach_it() throws Exception {
        // PR-15A-2 adds the customer HTTP surface; the ONLY placement it may call is placeCodOrder. The
        // create-only path (CREATED + RESERVED) stays package-private (ModuleBoundaryTest also forbids any
        // controller from calling it).
        java.lang.reflect.Method createOnly = OrderService.class.getDeclaredMethod("createOrder", CustomerId.class,
                String.class, PaymentMethod.class);
        assertThat(java.lang.reflect.Modifier.isPublic(createOnly.getModifiers())).isFalse();
        java.lang.reflect.Method cod = OrderService.class.getDeclaredMethod("placeCodOrder", CustomerId.class,
                String.class);
        assertThat(java.lang.reflect.Modifier.isPublic(cod.getModifiers())).isTrue();
    }
}
