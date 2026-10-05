package com.tazzzo.customer.order;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.tazzzo.benefits.TransactionalBenefitsEvaluationPort;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerIdentityAuthority;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.read.TransactionalCatalogCardReadPort;
import com.tazzzo.customer.address.AddressRepository;
import com.tazzzo.customer.cart.CartPurchaseIntegrityException;
import com.tazzzo.customer.cart.CartPurchasePort;
import com.tazzzo.customer.checkout.CheckoutQuote;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.customer.checkout.CheckoutQuoteRepository;
import com.tazzzo.inventory.InventoryReservation;
import com.tazzzo.inventory.InventoryReservationFailure;
import com.tazzzo.inventory.InventoryReservationId;
import com.tazzzo.inventory.InventoryReservationPort;
import com.tazzzo.inventory.InventoryReservationStatus;
import com.tazzzo.inventory.PreparedInventoryReservation;
import com.tazzzo.pricing.TransactionalPriceReadPort;
import com.tazzzo.serviceability.TransactionalServiceabilityReadPort;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Order orchestration. Two operations share ONE skeleton ({@link #execute}) and ONE set of
 * transaction-internal authority checks ({@link OrderDraftAssembler}); they differ only in how they
 * FINISH inside the single outer transaction:
 *
 * <pre>
 * createOrder    (create-only, INTERNAL — no customer-reachable caller):
 *     validate -> reserve -> insert CREATED (version 1)          [reservation stays RESERVED]
 * placeCodOrder  (COD, the domain operation PR-15A-2's HTTP surface will call):
 *     validate -> reserve -> consume -> finalize cart -> insert CONFIRMED (version 2)
 * </pre>
 *
 * A COD placement NEVER commits an intermediate {@code CREATED} Order, so there is no recoverable
 * two-transaction gap to get stuck in (a committed {@code CREATED} whose hold later expires could
 * never confirm and would block its quote forever). {@code CREATED} remains only for a future prepaid
 * composition, which genuinely waits on an external dependency.
 *
 * <p><b>The ratified idempotency invariant — "once an Order exists, it wins over EVERY later mutable
 * authority" — holds BY CONSTRUCTION:</b> the ONLY reads before the existing-Order check (both the
 * pre-transaction fast path and the in-transaction authoritative one) are the Order lookup itself and
 * the immutable {@link CheckoutQuoteRepository#findOwnedQuote}, which deliberately does NOT judge
 * quote expiry. Everything mutable — including the cart-purchase guard — is read EXCLUSIVELY inside
 * the transaction, strictly AFTER the in-transaction existing-Order check, so a same-quote replay can
 * never be diverted into a failure it should never see again (quote expiry, address, serviceability,
 * price, catalog, stock, or {@code CART_VERSION_ALREADY_PURCHASED}).
 *
 * <p><b>COD sequence inside the one {@link Tx#call}</b> (mutable work only, every attempt):
 * <ol>
 *   <li>existing-Order replay (authoritative) — {@code CONFIRMED} returned as-is, nothing touched;</li>
 *   <li>live quote expiry, customer identity ({@link OrderDraftAssembler#preflight});</li>
 *   <li><b>cart-purchase guard</b> — {@code purchasedThroughVersion >= quote.cartVersion} means a
 *       DIFFERENT quote from this cart intent already placed: {@code CART_VERSION_ALREADY_PURCHASED}.
 *       Placed BEFORE the expensive lifecycle writes so the loser of a race does no stock work;</li>
 *   <li>address, serviceability, pricing, catalog, allocation, reserve
 *       ({@link OrderDraftAssembler#validateAndReserve});</li>
 *   <li>consume the reservation immediately (Inventory owns the transition and the stock math);
 *       linkage to THIS order is verified, else {@code INTEGRITY_FAILURE} aborts everything;</li>
 *   <li>{@link CartPurchasePort#finalizePurchase} in the SAME session — clears the cart only if it is
 *       still the purchased version, always raises the marker, never destroys newer edits;</li>
 *   <li>insert the {@code CONFIRMED} Order.</li>
 * </ol>
 * One commit or one rollback covers all of it: {@code CONFIRMED} can never coexist with a
 * {@code RESERVED}/{@code RELEASED} reservation, a consumed reservation can never exist without its
 * Order, the marker can never advance without the Order, and there is no post-commit best-effort step.
 * A COD reservation is never committed in {@code RESERVED} state, so the expiry worker has nothing to
 * race: if a release commits first the placement fails {@code RESERVATION_EXPIRED} with nothing
 * written; if the placement commits first the reservation is {@code CONSUMED} and a later release
 * throws {@code INVALID_TRANSITION} (which the worker already treats as an expected lost race).
 *
 * <p><b>Retry safety:</b> {@link OrderId} and {@link InventoryReservationPort#prepare} are fixed ONCE
 * before the transaction (a retry reuses the same identity/expiry); the allocation, every live read,
 * {@code confirmedAt} and the Order are rebuilt inside each attempt and only the COMMITTED attempt's
 * value is returned (straight from {@code Tx.call}, never a captured holder). An aborted attempt's
 * reserve, consume, cart write and insert all roll back together.
 *
 * <p><b>Metrics</b> are recorded only here, after the outer operation returns; never by the assembler,
 * Inventory, or CartPurchase (they cannot know whether this transaction commits).
 *
 * <p><b>Benefits snapshot (PR-18A-1):</b> inside the SAME outer {@code Tx.call}, after the durable-replay check and
 * the canonical price revalidation and before the reserve, {@link OrderDraftAssembler} evaluates Benefits through
 * {@code TransactionalBenefitsEvaluationPort} in the caller's session over the Order's canonical merchandise
 * subtotal, and the immutable {@link OrderBenefitSnapshot} is persisted with the Order (every new Order carries
 * one, including normal no-benefit outcomes; absence means a legacy Order). A durable replay returns the stored Order
 * untouched and NEVER re-evaluates Benefits. The authoritative result is the Mongo transaction snapshot this attempt
 * observed: a Membership revoke committed before that read is seen; one committed after may still commit with the
 * entitlement this attempt observed (the same accepted snapshot semantics as the Pricing check — a stronger
 * serializability model is a separate cross-domain design change). Canonical Pricing money is never rewritten.
 *
 * <p><b>Money snapshot (PR-20A):</b> in the same transaction, after Pricing revalidation and the Benefits evaluation and
 * before the reserve, {@link OrderDraftAssembler} builds the immutable {@link OrderMoneySnapshot}
 * ({@code payablePaise = merchandiseSubtotalPaise - benefitDiscountPaise}, V1: tax-inclusive prices, no fees, coupons,
 * spendable coins or wallet) and it is persisted with the Order (every new Order carries one). A durable replay and
 * duplicate-key recovery return the stored Order and never recompute it. No Payment state is implied.
 *
 * <p><b>Money model — PRE-Membership (documented):</b> {@code CheckoutQuote.Line.unitPricePaise} IS the
 * canonical {@code Pricing} selling price today, so a direct equality revalidation against
 * {@link TransactionalPriceReadPort} is correct. A future Membership/Benefits PR MUST NOT collapse
 * "the already-discounted line price" and "the canonical price Order revalidates against" into one
 * field: canonical merchandise price (Pricing), Membership entitlement and Benefits/Promotion discount
 * are separate concerns needing separate money-snapshot fields, each validated against its own
 * authority.
 *
 * <p><b>Cancellation rule (documented only, NOT implemented):</b> a future {@code CREATED}+
 * {@code RESERVED} cancellation may {@link InventoryReservationPort#release} inside the SAME
 * transaction as the status change. A {@code CONFIRMED}+{@code CONSUMED} order must NEVER call
 * {@code release} — physical stock has left; that needs an explicit compensation/restock flow.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private enum Mode { CREATE_ONLY, PLACE_COD }

    private final OrderRepository orders;
    private final CheckoutQuoteRepository checkoutQuotes;
    private final InventoryReservationPort reservationPort;
    private final CartPurchasePort cartPurchase;
    private final Clock clock;
    private final Tx tx;
    private final OrderObservability observability;
    private final OrderDraftAssembler assembler;
    private final com.tazzzo.delivery.DeliverySlotService deliverySlots;   // null in a wiring that offers no slots

    @org.springframework.beans.factory.annotation.Autowired
    public OrderService(OrderRepository orders, CheckoutQuoteRepository checkoutQuotes, AddressRepository addresses,
                        TransactionalServiceabilityReadPort serviceability, TransactionalPriceReadPort pricing,
                        TransactionalCatalogCardReadPort catalog, TransactionalBenefitsEvaluationPort benefits,
                        InventoryReservationPort reservationPort, CartPurchasePort cartPurchase, Clock clock,
                        ObjectProvider<CustomerIdentityAuthority> identityAuthority, Tx tx,
                        OrderObservability observability, com.tazzzo.delivery.DeliverySlotService deliverySlots) {
        this.orders = orders;
        this.checkoutQuotes = checkoutQuotes;
        this.reservationPort = reservationPort;
        this.cartPurchase = cartPurchase;
        this.clock = clock;
        this.tx = tx;
        this.observability = observability;
        this.deliverySlots = deliverySlots;
        this.assembler = new OrderDraftAssembler(addresses, serviceability, pricing, catalog, benefits,
                reservationPort, clock, identityAuthority);
    }

    /** A wiring with no delivery slots: a request that names a slot is refused (UNAVAILABLE), never silently ignored. */
    public OrderService(OrderRepository orders, CheckoutQuoteRepository checkoutQuotes, AddressRepository addresses,
                        TransactionalServiceabilityReadPort serviceability, TransactionalPriceReadPort pricing,
                        TransactionalCatalogCardReadPort catalog, TransactionalBenefitsEvaluationPort benefits,
                        InventoryReservationPort reservationPort, CartPurchasePort cartPurchase, Clock clock,
                        ObjectProvider<CustomerIdentityAuthority> identityAuthority, Tx tx,
                        OrderObservability observability) {
        this(orders, checkoutQuotes, addresses, serviceability, pricing, catalog, benefits, reservationPort, cartPurchase,
                clock, identityAuthority, tx, observability, null);
    }


    /**
     * INTERNAL create-only path: {@code CREATED} (version 1) + {@code RESERVED} reservation. Package-
     * private on purpose — NOT customer-reachable; kept for a future prepaid composition. Records
     * {@code order_create_*}.
     */
    Order createOrder(CustomerId customerId, String quoteIdRaw, PaymentMethod paymentMethod) {
        try {
            Order result = execute(customerId, quoteIdRaw, paymentMethod, Mode.CREATE_ONLY, null);
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

    /**
     * The COD placement domain operation: one atomic transaction producing a {@code CONFIRMED} Order,
     * a {@code CONSUMED} reservation, an advanced {@code purchasedThroughVersion} and (only when still
     * current) a cleared source cart. Idempotent by {@code (customerId, quoteId)}: a replay returns the
     * same {@code CONFIRMED} Order with no reserve, no consume and no cart mutation. Records
     * {@code order_place_cod_*}.
     */
    public Order placeCodOrder(CustomerId customerId, String quoteIdRaw) {
        return placeCodOrder(customerId, quoteIdRaw, null);
    }

    /**
     * As above, optionally reserving a delivery slot ({@code <window>~<yyyy-MM-dd>}) for the order in the SAME transaction:
     * a refused or full slot aborts the whole placement (stock, cart marker and order all roll back), so a CONFIRMED order
     * never exists without the slot it asked for. A durable replay returns the original order and never re-reserves.
     */
    public Order placeCodOrder(CustomerId customerId, String quoteIdRaw, String deliverySlotId) {
        try {
            Order result = execute(customerId, quoteIdRaw, PaymentMethod.COD, Mode.PLACE_COD, deliverySlotId);
            observability.placeCodSuccess(); // placed-or-replayed, after the outer operation returned
            return result;
        } catch (OrderFailure e) {
            observability.placeCodFailure(e.reason());
            throw e;
        } catch (MongoException e) {
            log.error("customer_order_place_cod_failed type={}", e.getClass().getSimpleName());
            observability.placeCodFailure(OrderFailure.Reason.UNAVAILABLE);
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "datastore unavailable during order placement");
        }
    }

    /**
     * PR-15A-2 — the owned read behind {@code GET /v1/customer/orders/{orderId}}. Returns the Order's
     * stored immutable snapshot; never re-reads Product/Address/Pricing. A malformed id, an unknown id,
     * another customer's Order, and an internal {@code CREATED} row (an intermediate state no customer
     * operation may observe) are ONE outcome: {@code ORDER_NOT_FOUND}. A corrupt persisted row is not
     * caught here: it fails loud ({@code IllegalStateException}/{@code IllegalArgumentException}) and the
     * HTTP boundary maps it to a safe 500. Records no domain metric (reads are counted at the HTTP layer).
     */
    public Order getOrder(CustomerId customerId, String orderIdRaw) {
        if (!OrderId.isValid(orderIdRaw)) {
            throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
        }
        Document stored;
        try {
            stored = orders.findOwnedById(orderIdRaw, customerId.value());
        } catch (MongoException e) {
            log.error("customer_order_read_failed type={}", e.getClass().getSimpleName());
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "datastore unavailable during order read");
        }
        if (stored == null) {
            throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);
        }
        Order order = OrderRepository.toOrder(stored);
        if (order.status() == OrderStatus.CREATED) {
            throw new OrderFailure(OrderFailure.Reason.ORDER_NOT_FOUND);   // CREATED is internal
        }
        return order;
    }

    private Order execute(CustomerId customerId, String quoteIdRaw, PaymentMethod paymentMethod, Mode mode,
                          String deliverySlotId) {
        // 0 -- durable replay fast-path, BEFORE anything about the source quote is touched.
        Document existing = orders.findByCustomerAndQuote(customerId.value(), quoteIdRaw);
        if (existing != null) {
            return replay(OrderRepository.toOrder(existing), mode);
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
        // authority is read out here.
        OrderId orderId = OrderId.generate();
        PreparedInventoryReservation prepared = reservationPort.prepare(orderId.value());
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        try {
            return tx.call(session -> {
                // durable authority FIRST, again, inside the transaction -- strictly before the
                // cart-purchase guard and every other mutable read.
                Document existingInTx = orders.findByCustomerAndQuote(session, customerId.value(), quoteIdRaw);
                if (existingInTx != null) {
                    return replay(OrderRepository.toOrder(existingInTx), mode);
                }

                assembler.preflight(session, customerId, quote);

                if (mode == Mode.PLACE_COD) {
                    requireSourceCartNotPurchased(session, customerId, quote);
                }

                OrderDraftAssembler.Draft draft = assembler.validateAndReserve(session, customerId, quote, prepared);

                return mode == Mode.CREATE_ONLY
                        ? finishCreateOnly(session, customerId, quoteIdRaw, quote, orderId, paymentMethod, draft,
                                createdAt)
                        : finishCod(session, customerId, quoteIdRaw, quote, orderId, draft, createdAt, deliverySlotId);
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                return recoverFromDuplicateKey(customerId, quoteIdRaw, mode);
            }
            throw e;
        }
    }

    /**
     * Duplicate-key (11000) recovery. Business correctness deliberately does NOT depend on anything
     * MongoDB says about WHICH unique invariant tripped (no index name, no message text): the proof is
     * the durable row, read OUTSIDE the failed transaction (which aborted and committed nothing).
     * <ul>
     *   <li>A same-(customerId, quoteId) Order exists: it is the authoritative result of this
     *       idempotent request — whether this attempt lost the same-quote race or merely ALSO tripped
     *       some other duplicate — so it is reconstructed strictly and passed through {@link #replay}
     *       (for COD a {@code CREATED} winner therefore still fails closed, never converted).</li>
     *   <li>No such Order exists: the duplicate came from some other unique invariant or an
     *       inconsistent condition, and is never laundered into a replay: {@code INTEGRITY_FAILURE}.</li>
     * </ul>
     */
    private Order recoverFromDuplicateKey(CustomerId customerId, String quoteIdRaw, Mode mode) {
        Document winner = orders.findByCustomerAndQuote(customerId.value(), quoteIdRaw);
        if (winner != null) {
            return replay(OrderRepository.toOrder(winner), mode);
        }
        log.error("customer_order_duplicate_key_without_same_quote_order");
        throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE,
                "duplicate key with no existing order for this customer and quote");
    }

    /** What an already-durable Order means to each mode. A COD placement only ever produces
     *  {@code CONFIRMED}; meeting a {@code CREATED} row there is not something this path created and is
     *  never silently converted — it fails closed. */
    private static Order replay(Order existing, Mode mode) {
        // a CANCELLED order is the honest, idempotent answer for a re-sent placement of the same quote (never re-created)
        if (mode == Mode.PLACE_COD && existing.status() == OrderStatus.CREATED) {
            throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE,
                    "existing order for this quote is not CONFIRMED; COD placement does not convert it");
        }
        return existing;
    }

    private void requireSourceCartNotPurchased(ClientSession session, CustomerId customerId, CheckoutQuote quote) {
        boolean purchased;
        try {
            purchased = cartPurchase.isSourceVersionPurchased(session, customerId, quote.cartVersion());
        } catch (CartPurchaseIntegrityException e) {
            throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE, "cart purchase state corrupt");
        }
        if (purchased) {
            throw new OrderFailure(OrderFailure.Reason.CART_VERSION_ALREADY_PURCHASED);
        }
    }

    private Order finishCreateOnly(ClientSession session, CustomerId customerId, String quoteIdRaw,
                                   CheckoutQuote quote, OrderId orderId, PaymentMethod paymentMethod,
                                   OrderDraftAssembler.Draft draft, Instant createdAt) {
        Order order = new Order(orderId, customerId.value(), quoteIdRaw, OrderStatus.CREATED, paymentMethod, 1L,
                quote.addressId(), quote.addressVersion(), draft.addressSnapshot(), draft.lines(),
                quote.itemCount(), quote.subtotalPaise(), quote.currency(), draft.reservation().reservationId(),
                null, createdAt, null, clock.instant(), draft.benefitSnapshot(), draft.moneySnapshot());
        orders.insert(session, order);
        return order;
    }

    private Order finishCod(ClientSession session, CustomerId customerId, String quoteIdRaw, CheckoutQuote quote,
                            OrderId orderId, OrderDraftAssembler.Draft draft, Instant createdAt, String deliverySlotId) {
        String reservationId = draft.reservation().reservationId();

        // the delivery slot, in this same transaction: any refusal aborts everything written so far in this attempt
        OrderDeliverySlot slot = null;
        if (deliverySlotId != null) {
            slot = reserveSlot(session, draft, deliverySlotId, orderId);
        }

        // consume immediately -- Inventory owns expiry, the RESERVED->CONSUMED transition and the
        // on_hand/reserved math; Order only maps its failures.
        InventoryReservation consumed;
        try {
            consumed = reservationPort.consume(session, new InventoryReservationId(reservationId));
        } catch (InventoryReservationFailure e) {
            throw OrderDraftAssembler.mapConsumeFailure(e);
        }
        if (!orderId.value().equals(consumed.orderId()) || !reservationId.equals(consumed.reservationId())
                || consumed.status() != InventoryReservationStatus.CONSUMED) {
            // wrong linkage: abort the whole transaction, consume included.
            throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE,
                    "consumed reservation does not belong to this order");
        }

        // cart finalization in the SAME session: a datastore/integrity failure aborts the placement;
        // a newer cart is a normal, preserved outcome.
        try {
            cartPurchase.finalizePurchase(session, customerId, quote.cartVersion());
        } catch (CartPurchaseIntegrityException e) {
            throw new OrderFailure(OrderFailure.Reason.INTEGRITY_FAILURE, "cart purchase finalization failed");
        }

        // confirmedAt is read fresh INSIDE the callback each attempt (a rolled-back attempt leaks
        // nothing), millisecond-truncated for Mongo, and never before createdAt.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant confirmedAt = now.isBefore(createdAt) ? createdAt : now;
        Order order = new Order(orderId, customerId.value(), quoteIdRaw, OrderStatus.CONFIRMED, PaymentMethod.COD,
                2L, quote.addressId(), quote.addressVersion(), draft.addressSnapshot(), draft.lines(),
                quote.itemCount(), quote.subtotalPaise(), quote.currency(), reservationId,
                ConfirmedPaymentCondition.COD_DUE, createdAt, confirmedAt, confirmedAt, draft.benefitSnapshot(),
                draft.moneySnapshot(), slot);
        orders.insert(session, order);
        return order;
    }

    private OrderDeliverySlot reserveSlot(ClientSession session, OrderDraftAssembler.Draft draft, String slotId, OrderId orderId) {
        if (deliverySlots == null) {
            throw new OrderFailure(OrderFailure.Reason.UNAVAILABLE, "delivery slots are not available in this wiring");
        }
        com.tazzzo.delivery.SlotChoice choice;
        try {
            choice = deliverySlots.reserveForOrder(session,
                    new com.tazzzo.commerce.contract.Pincode(draft.addressSnapshot().postalCode()), slotId, orderId.value());
        } catch (com.tazzzo.delivery.SlotRefusedException e) {
            // INVALID is caught earlier at the HTTP edge; reaching here with it is still a caller error, never a 500
            throw new OrderFailure(e.reason() == com.tazzzo.delivery.SlotRefusedException.Reason.INVALID
                    ? OrderFailure.Reason.INVALID_REQUEST : OrderFailure.Reason.SLOT_UNAVAILABLE);
        }
        return new OrderDeliverySlot(choice.serviceAreaId(), choice.windowId(), choice.date(), choice.label(),
                choice.startsAt(), choice.endsAt());
    }
}
