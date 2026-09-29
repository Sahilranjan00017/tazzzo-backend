package com.tazzzo.customer.order;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.read.CatalogCardFacts;
import com.tazzzo.commerce.read.TransactionalCatalogCardReadPort;
import com.tazzzo.common.money.Currency;
import com.tazzzo.customer.address.AddressRepository;
import com.tazzzo.customer.checkout.CheckoutQuote;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.customer.checkout.CheckoutQuoteRepository;
import com.tazzzo.inventory.InventoryReservation;
import com.tazzzo.inventory.InventoryReservationAllocation;
import com.tazzzo.inventory.InventoryReservationFailure;
import com.tazzzo.inventory.InventoryReservationItem;
import com.tazzzo.inventory.InventoryReservationPort;
import com.tazzzo.inventory.PreparedInventoryReservation;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.TransactionalPriceReadPort;
import com.tazzzo.serviceability.ServiceabilityResolution;
import com.tazzzo.serviceability.TransactionalServiceabilityReadPort;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PR-14B — Order Foundation orchestration. Creates an internal {@code CREATED} Order from an
 * owned, unexpired {@link CheckoutQuote}, atomically alongside an {@code Inventory} reservation.
 * PRE-Payment, PRE-Membership: no payment field, no discount/tax/fee field exists on {@link Order}
 * yet (see the money-model note below); the customer-facing HTTP surface, {@code CONFIRMED}
 * transition and cancellation are explicitly future work (PR-15A+), documented but not implemented
 * here.
 *
 * <p><b>The ratified idempotency invariant — "once an Order exists, it wins over EVERY later
 * mutable authority" — is true here BY CONSTRUCTION, not by convention:</b> the ONLY reads this
 * method performs before its existing-Order check (both the pre-transaction fast path and the
 * in-transaction authoritative one) are the Order lookup itself and the immutable
 * {@link CheckoutQuoteRepository#findOwnedQuote}, which deliberately does NOT judge quote expiry.
 * Address, serviceability, pricing, catalog eligibility and inventory are read EXCLUSIVELY inside
 * the transaction, strictly AFTER the in-transaction existing-Order check — so a request whose
 * Order already committed can never be diverted into a mutable-state failure it should never see
 * again. See {@link #doCreate} for the exact sequence.
 *
 * <p><b>Preparation-contract evolution (PR-14B):</b> {@link InventoryReservationPort#prepare}
 * fixes ONLY Inventory-owned identity/lifetime from an {@code orderId} — no routing, no items — so
 * this class never needs to resolve serviceability before entering its own transaction. The
 * caller-supplied {@link InventoryReservationAllocation} (fulfillment location + items) is
 * constructed FRESH, INSIDE the transaction callback, from whatever route THIS attempt's own
 * session-aware serviceability read returns — safe even across a {@code Tx.call} driver retry,
 * since an aborted attempt commits nothing.
 *
 * <p><b>Session-aware authority set — ALL inside the one outer {@link Tx#call}:</b> customer
 * identity ({@link CustomerIdentityAuthority}), address ({@link AddressRepository}), serviceability
 * ({@link TransactionalServiceabilityReadPort}), pricing ({@link TransactionalPriceReadPort}),
 * catalog eligibility ({@link TransactionalCatalogCardReadPort}), and the inventory reservation
 * ({@link InventoryReservationPort}). Only {@link CheckoutQuote} itself is loaded before the
 * transaction — it is immutable once created, so there is nothing mutable to re-read; its temporal
 * validity (never its existence/ownership) is judged fresh, inside, against a LIVE {@link Clock}.
 *
 * <p><b>Money model — PRE-Membership (documented, per this PR's ratified forward contract):</b>
 * today, {@code CheckoutQuote.Line.unitPricePaise} IS the canonical {@code Pricing} selling price,
 * so a direct equality revalidation against {@link TransactionalPriceReadPort} is correct. A future
 * Membership/Benefits PR MUST NOT collapse "the already-discounted line price" and "the canonical
 * price Order revalidates against" into one field: canonical merchandise price (Pricing), Membership
 * entitlement, and Benefits/Promotion discount are three separate concerns that need three separate
 * future money-snapshot fields, each validated against its own authority — never hardcode a
 * percentage or a flat rupee amount here when that day comes.
 *
 * <p><b>Future confirmation invariant (documented only, NOT implemented in this PR):</b> a future
 * Order may transition to {@code CONFIRMED} only when (a) a payment condition is satisfied — a
 * future COD flow's {@code COD_DUE}, or a future prepaid flow's {@code SUCCEEDED} — AND (b) the
 * Inventory reservation transitions to {@code CONSUMED} atomically WITH that same status change, in
 * one transaction. This PR's Order always stays {@code CREATED} with its reservation
 * {@code RESERVED}.
 *
 * <p><b>Future cancellation rule (documented only, NOT implemented in this PR):</b> a future
 * {@code CREATED}+{@code RESERVED} cancellation may call {@link InventoryReservationPort#release}
 * inside the SAME transaction as the status change. A future {@code CONFIRMED}+{@code CONSUMED}
 * order must NEVER call {@code release} — physical stock has already left; that case needs an
 * explicit compensation/restock domain flow this PR does not build.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final CheckoutQuoteRepository checkoutQuotes;
    private final AddressRepository addresses;
    private final TransactionalServiceabilityReadPort serviceability;
    private final TransactionalPriceReadPort pricing;
    private final TransactionalCatalogCardReadPort catalog;
    private final InventoryReservationPort reservationPort;
    private final Clock clock;
    private final ObjectProvider<CustomerIdentityAuthority> identityAuthority;
    private final Tx tx;
    private final OrderObservability observability;

    public OrderService(OrderRepository orders, CheckoutQuoteRepository checkoutQuotes, AddressRepository addresses,
                        TransactionalServiceabilityReadPort serviceability, TransactionalPriceReadPort pricing,
                        TransactionalCatalogCardReadPort catalog, InventoryReservationPort reservationPort,
                        Clock clock, ObjectProvider<CustomerIdentityAuthority> identityAuthority, Tx tx,
                        OrderObservability observability) {
        this.orders = orders;
        this.checkoutQuotes = checkoutQuotes;
        this.addresses = addresses;
        this.serviceability = serviceability;
        this.pricing = pricing;
        this.catalog = catalog;
        this.reservationPort = reservationPort;
        this.clock = clock;
        this.identityAuthority = identityAuthority;
        this.tx = tx;
        this.observability = observability;
    }

    public Order createOrder(CustomerId customerId, String quoteIdRaw) {
        try {
            Order result = doCreate(customerId, quoteIdRaw);
            observability.success(); // create-or-replay, see OrderObservability's javadoc
            return result;
        } catch (OrderFailure e) {
            observability.failure(e.reason());
            throw e;
        } catch (MongoException e) {
            log.error("customer_order_create_failed type={}", e.getClass().getSimpleName());
            observability.failure(OrderFailure.Reason.UNAVAILABLE);
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "datastore unavailable during order creation");
        }
    }

    private Order doCreate(CustomerId customerId, String quoteIdRaw) {
        // 0 -- durable replay fast-path, BEFORE anything about the source quote is touched.
        Document existing = orders.findByCustomerAndQuote(customerId.value(), quoteIdRaw);
        if (existing != null) {
            return OrderRepository.toOrder(existing);
        }

        // 1 -- immutable owned quote load. NOT the expiry authority (see findOwnedQuote's javadoc).
        if (!CheckoutQuoteId.isValid(quoteIdRaw)) {
            throw new OrderFailure(OrderFailure.Reason.QUOTE_NOT_FOUND);
        }
        CheckoutQuote quote = checkoutQuotes.findOwnedQuote(quoteIdRaw, customerId.value());
        if (quote == null) {
            throw new OrderFailure(OrderFailure.Reason.QUOTE_NOT_FOUND);
        }

        // 2/3 -- Inventory-owned identity/lifetime, fixed ONCE before the transaction. No mutable
        // authority (address/serviceability/price/catalog/stock) is read out here.
        OrderId orderId = OrderId.generate();
        PreparedInventoryReservation prepared = reservationPort.prepare(orderId.value());
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        try {
            return tx.call(session -> {
                // 1 -- durable authority FIRST, again, inside the transaction.
                Document existingInTx = orders.findByCustomerAndQuote(session, customerId.value(), quoteIdRaw);
                if (existingInTx != null) {
                    return OrderRepository.toOrder(existingInTx);
                }

                // 2 -- live quote expiry, fresh clock, every attempt.
                if (quote.isExpired(clock.instant())) {
                    throw new OrderFailure(OrderFailure.Reason.QUOTE_EXPIRED);
                }

                // 3 -- customer identity.
                verifyIdentityExists(session, customerId);

                // 4 -- address: exact id+version match, snapshot built from THIS document.
                Document currentAddress = addresses.findOwnedById(session, customerId.value(), quote.addressId());
                if (currentAddress == null
                        || currentAddress.get("version", Number.class).longValue() != quote.addressVersion()) {
                    throw new OrderFailure(OrderFailure.Reason.ADDRESS_CHANGED);
                }
                OrderAddressSnapshot addressSnapshot = snapshotFrom(currentAddress);

                // 5 -- serviceability: ONE authoritative route, read inside the transaction. No
                // stale pre-transaction route exists to compare against.
                ServiceabilityResolution route = serviceability.resolveByPincode(session,
                        new Pincode(currentAddress.getString("postalCode")));
                if (route.status() != ServiceabilityResolution.Status.SERVICEABLE) {
                    throw new OrderFailure(OrderFailure.Reason.NOT_SERVICEABLE);
                }

                // 6 -- pricing: current canonical selling price must exactly equal the quote's.
                List<String> skuIds = quote.lines().stream().map(CheckoutQuote.Line::skuId).toList();
                Map<String, PriceLookup> prices = pricing.findCurrentPrices(session, skuIds, Currency.INR);
                for (CheckoutQuote.Line line : quote.lines()) {
                    PriceLookup lookup = prices.get(line.skuId());
                    if (lookup == null || !lookup.isUsable()
                            || lookup.price().sellingPricePaise() != line.unitPricePaise()) {
                        throw new OrderFailure(OrderFailure.Reason.PRICE_CHANGED);
                    }
                }

                // 7 -- catalog eligibility + display snapshot, from THIS transaction's state.
                List<OrderLine> orderLines = new ArrayList<>(quote.lines().size());
                for (CheckoutQuote.Line line : quote.lines()) {
                    Optional<CatalogCardFacts> facts = catalog.findEligibleCard(session, line.skuId());
                    if (facts.isEmpty()) {
                        throw new OrderFailure(OrderFailure.Reason.PRODUCT_UNAVAILABLE);
                    }
                    CatalogCardFacts f = facts.get();
                    orderLines.add(new OrderLine(line.skuId(), f.title(), f.brandCode(), line.quantity(),
                            line.unitPricePaise(), line.lineTotalPaise()));
                }

                // 8 -- allocation constructed fresh, inside the callback, from THIS route + the
                // immutable quote's items.
                List<InventoryReservationItem> items = quote.lines().stream()
                        .map(l -> new InventoryReservationItem(l.skuId(), l.quantity())).toList();
                InventoryReservationAllocation allocation =
                        new InventoryReservationAllocation(route.fulfillmentLocationId(), items);

                // 9 -- reserve, mapping Inventory's failure vocabulary deliberately.
                InventoryReservation reservation;
                try {
                    reservation = reservationPort.reserve(session, prepared, allocation);
                } catch (InventoryReservationFailure e) {
                    throw mapInventoryFailure(e);
                }

                // 10/11 -- construct from ONLY values validated in THIS transaction, then insert.
                Order order = new Order(orderId, customerId.value(), quoteIdRaw, OrderStatus.CREATED,
                        quote.addressId(), quote.addressVersion(), addressSnapshot, orderLines, quote.itemCount(),
                        quote.subtotalPaise(), quote.currency(), reservation.reservationId(), createdAt,
                        clock.instant());
                orders.insert(session, order);
                return order;
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // lost a concurrent same-(customer,quote) create race: resolve to the winner's ONE
                // durable Order, AS-IS -- no revalidation of quote/address/serviceability/price/
                // catalog/stock (the winner's own transaction already validated all of it).
                Document winner = orders.findByCustomerAndQuote(customerId.value(), quoteIdRaw);
                if (winner != null) {
                    return OrderRepository.toOrder(winner);
                }
                log.error("customer_order_duplicate_key_no_winner");
                throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE,
                        "duplicate-key error with no winning order row found");
            }
            throw e;
        }
    }

    /** Maps Inventory's failure vocabulary onto Order's own -- Inventory's exception type/message
     *  never escapes this boundary raw. */
    private static OrderFailure mapInventoryFailure(InventoryReservationFailure e) {
        return switch (e.reason()) {
            case RESERVATION_UNAVAILABLE -> new OrderFailure(OrderFailure.Reason.STOCK_UNAVAILABLE, e.getMessage());
            case RESERVATION_EXPIRED -> new OrderFailure(OrderFailure.Reason.RESERVATION_EXPIRED, e.getMessage());
            case ALREADY_RESERVED_DIFFERENT_INPUT, INTEGRITY_FAILURE ->
                    new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE, e.getMessage());
            case UNAVAILABLE -> new OrderFailure(OrderFailure.Reason.UNAVAILABLE, e.getMessage());
            // NOT_FOUND/INVALID_TRANSITION/INVALID_REQUEST are not reachable from reserve() as Order
            // calls it; mapped conservatively rather than left to propagate raw.
            default -> new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE,
                    "unexpected inventory failure: " + e.reason());
        };
    }

    private static OrderAddressSnapshot snapshotFrom(Document a) {
        return new OrderAddressSnapshot(a.getString("label"), a.getString("recipientName"),
                a.getString("recipientPhone"), a.getString("addressLine1"), a.getString("addressLine2"),
                a.getString("landmark"), a.getString("city"), a.getString("state"), a.getString("postalCode"),
                a.get("latitude", Number.class).doubleValue(), a.get("longitude", Number.class).doubleValue());
    }

    private void verifyIdentityExists(ClientSession session, CustomerId customerId) {
        CustomerIdentityAuthority authority = identityAuthority.getIfAvailable();
        boolean exists;
        try {
            exists = authority != null && authority.exists(session, customerId);
        } catch (RuntimeException e) {
            log.error("customer_identity_authority_failed type={}", e.getClass().getSimpleName());
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE);
        }
        if (!exists) {
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE);
        }
    }
}
