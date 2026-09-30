package com.tazzzo.customer.order;

import com.mongodb.client.ClientSession;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.read.CatalogCardFacts;
import com.tazzzo.commerce.read.TransactionalCatalogCardReadPort;
import com.tazzzo.common.money.Currency;
import com.tazzzo.customer.address.AddressRepository;
import com.tazzzo.customer.checkout.CheckoutQuote;
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

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PR-15A-1 — the ONE implementation of the authoritative, transaction-internal checks every Order
 * creation shares (extracted verbatim from PR-14B's {@code OrderService.doCreate}, not re-derived):
 * quote expiry, customer identity, exact address version, ONE authoritative serviceability route,
 * exact canonical-price revalidation, catalog eligibility + display snapshot, allocation built FRESH
 * from this attempt's route, and the Inventory reserve. Both the create-only path and COD placement
 * call it, so no serviceability/pricing/catalog algorithm exists twice.
 *
 * <p><b>Session-aware discipline</b> (same rule {@code InventoryReservationPort} states): every method
 * joins the CALLER's transaction and MUST NEVER start one, record a success metric, or read a mutable
 * authority outside the session it is given. It cannot know whether its caller commits, so all
 * metrics belong to {@code OrderService}'s outer operation. Package-private on purpose: no public port
 * is needed, and nothing outside {@code customer.order} may compose these steps on its own.
 *
 * <p>The work is split in two so COD placement can put its cart-purchase guard BETWEEN the cheap
 * checks ({@link #preflight}) and the expensive/destructive lifecycle writes
 * ({@link #validateAndReserve}); create-only calls them back to back.
 */
final class OrderDraftAssembler {

    private static final Logger log = LoggerFactory.getLogger(OrderDraftAssembler.class);

    /** What the shared checks validated, ready for the caller to finish: the frozen snapshots and the
     *  Inventory-owned reservation reserved in the caller's session. */
    record Draft(OrderAddressSnapshot addressSnapshot, List<OrderLine> lines, InventoryReservation reservation) {
    }

    private final AddressRepository addresses;
    private final TransactionalServiceabilityReadPort serviceability;
    private final TransactionalPriceReadPort pricing;
    private final TransactionalCatalogCardReadPort catalog;
    private final InventoryReservationPort reservationPort;
    private final Clock clock;
    private final ObjectProvider<CustomerIdentityAuthority> identityAuthority;

    OrderDraftAssembler(AddressRepository addresses, TransactionalServiceabilityReadPort serviceability,
                        TransactionalPriceReadPort pricing, TransactionalCatalogCardReadPort catalog,
                        InventoryReservationPort reservationPort, Clock clock,
                        ObjectProvider<CustomerIdentityAuthority> identityAuthority) {
        this.addresses = addresses;
        this.serviceability = serviceability;
        this.pricing = pricing;
        this.catalog = catalog;
        this.reservationPort = reservationPort;
        this.clock = clock;
        this.identityAuthority = identityAuthority;
    }

    /** Live quote expiry (fresh clock, every attempt) and customer identity. No writes. */
    void preflight(ClientSession session, CustomerId customerId, CheckoutQuote quote) {
        if (quote.isExpired(clock.instant())) {
            throw new OrderFailure(OrderFailure.Reason.QUOTE_EXPIRED);
        }
        verifyIdentityExists(session, customerId);
    }

    /** Address, serviceability, pricing, catalog, allocation, reserve — in the caller's session. */
    Draft validateAndReserve(ClientSession session, CustomerId customerId, CheckoutQuote quote,
                             PreparedInventoryReservation prepared) {
        // address: exact id+version match, snapshot built from THIS document.
        Document currentAddress = addresses.findOwnedById(session, customerId.value(), quote.addressId());
        if (currentAddress == null
                || currentAddress.get("version", Number.class).longValue() != quote.addressVersion()) {
            throw new OrderFailure(OrderFailure.Reason.ADDRESS_CHANGED);
        }
        OrderAddressSnapshot addressSnapshot = snapshotFrom(currentAddress);

        // serviceability: ONE authoritative route, read inside the transaction. No stale
        // pre-transaction route exists to compare against.
        ServiceabilityResolution route = serviceability.resolveByPincode(session,
                new Pincode(currentAddress.getString("postalCode")));
        if (route.status() != ServiceabilityResolution.Status.SERVICEABLE) {
            throw new OrderFailure(OrderFailure.Reason.NOT_SERVICEABLE);
        }

        // pricing: current canonical selling price must exactly equal the quote's.
        List<String> skuIds = quote.lines().stream().map(CheckoutQuote.Line::skuId).toList();
        Map<String, PriceLookup> prices = pricing.findCurrentPrices(session, skuIds, Currency.INR);
        for (CheckoutQuote.Line line : quote.lines()) {
            PriceLookup lookup = prices.get(line.skuId());
            if (lookup == null || !lookup.isUsable()
                    || lookup.price().sellingPricePaise() != line.unitPricePaise()) {
                throw new OrderFailure(OrderFailure.Reason.PRICE_CHANGED);
            }
        }

        // catalog eligibility + display snapshot, from THIS transaction's state.
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

        // allocation constructed fresh, inside the callback, from THIS route + the immutable quote's items.
        List<InventoryReservationItem> items = quote.lines().stream()
                .map(l -> new InventoryReservationItem(l.skuId(), l.quantity())).toList();
        InventoryReservationAllocation allocation =
                new InventoryReservationAllocation(route.fulfillmentLocationId(), items);

        InventoryReservation reservation;
        try {
            reservation = reservationPort.reserve(session, prepared, allocation);
        } catch (InventoryReservationFailure e) {
            throw mapReserveFailure(e);
        }
        return new Draft(addressSnapshot, List.copyOf(orderLines), reservation);
    }

    /** Maps Inventory's failure vocabulary onto Order's own -- Inventory's exception type/message
     *  never escapes this boundary raw. */
    static OrderFailure mapReserveFailure(InventoryReservationFailure e) {
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

    /**
     * Consume-specific mapping. {@code INVALID_TRANSITION} from {@code consume} means the reservation
     * is {@code RELEASED} (the expiry worker or an explicit release won): from the placement's point
     * of view the hold is gone exactly as if it had expired, so it maps to {@code RESERVATION_EXPIRED}.
     * A reservation missing right after this same transaction reserved it is corruption, not expiry.
     */
    static OrderFailure mapConsumeFailure(InventoryReservationFailure e) {
        return switch (e.reason()) {
            case RESERVATION_EXPIRED, INVALID_TRANSITION ->
                    new OrderFailure(OrderFailure.Reason.RESERVATION_EXPIRED, e.getMessage());
            case UNAVAILABLE -> new OrderFailure(OrderFailure.Reason.UNAVAILABLE, e.getMessage());
            default -> new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE,
                    "inventory consume failed: " + e.reason());
        };
    }

    private static OrderAddressSnapshot snapshotFrom(Document a) {
        return new OrderAddressSnapshot(a.getString("label"), a.getString("recipientName"),
                a.getString("recipientPhone"), a.getString("addressLine1"), a.getString("addressLine2"),
                a.getString("landmark"), a.getString("city"), a.getString("state"), a.getString("postalCode"),
                nullableDouble(a, "latitude"), nullableDouble(a, "longitude"));
    }

    /** A coordinate-less saved address is VALID (both absent): preserve null, never call doubleValue()
     *  on it and never invent a default. A half pair is rejected by {@link OrderAddressSnapshot}. */
    private static Double nullableDouble(Document a, String field) {
        Number n = a.get(field, Number.class);
        return n == null ? null : n.doubleValue();
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
