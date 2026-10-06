package com.tazzzo.customer.order;

import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.inventory.InventoryReservationId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The immutable Order record: the durable result of one successful order transaction. Every value
 * carried here was validated/read INSIDE that SAME transaction — never a pre-transaction snapshot,
 * never re-derived afterward. The compact constructor validates every invariant a caller relies on
 * and fails LOUD (never normalizes), the same convention {@code CheckoutQuote}/
 * {@code InventoryReservation} established, including when reconstructing from a corrupt Mongo
 * document.
 *
 * <p><b>PR-15A-1 strict schema.</b> Adds {@code version}, {@code paymentMethod},
 * {@code confirmedPaymentCondition} and {@code confirmedAt}. There is NO compatibility shim and NO
 * default for a persisted row: a document missing a required field fails loudly (see
 * {@code OrderRepository.toOrder}). Rolling this schema out is gated on the deployed {@code orders}
 * collection being empty in every persistent environment — an operational prerequisite recorded in
 * {@code docs/ENGINEERING_STATUS.md}.
 *
 * <p><b>Status invariants</b> (the state machine, see {@link OrderStatus}):
 * <ul>
 *   <li>{@code CREATED}: {@code version == 1}, {@code confirmedAt == null},
 *       {@code confirmedPaymentCondition == null}.</li>
 *   <li>{@code CONFIRMED}: {@code version == 2}, {@code paymentMethod == COD},
 *       {@code confirmedPaymentCondition == COD_DUE}, {@code confirmedAt != null},
 *       {@code confirmedAt >= createdAt}, {@code updatedAt >= confirmedAt}.</li>
 * </ul>
 *
 * <p>Deliberately has no top-level {@code discountPaise}/{@code taxPaise}/{@code feePaise}/payable total: the
 * canonical merchandise money ({@code unitPricePaise}, {@code lineTotalPaise}, {@code subtotalPaise}) is Pricing's and
 * is never rewritten. The authoritative Benefits evaluation lives beside it as the separate immutable
 * {@link OrderBenefitSnapshot} (null ONLY on a legacy Order; every Order created by the Benefits-aware placement carries
 * one, including the normal no-benefit outcomes). See {@code OrderService}'s class-level documentation.
 */
public record Order(OrderId orderId, String customerId, String quoteId, OrderStatus status,
                    PaymentMethod paymentMethod, long version, String addressId, long addressVersion,
                    OrderAddressSnapshot addressSnapshot, List<OrderLine> lines, int itemCount,
                    long subtotalPaise, String currency, String reservationId,
                    ConfirmedPaymentCondition confirmedPaymentCondition, Instant createdAt, Instant confirmedAt,
                    Instant updatedAt, OrderBenefitSnapshot benefitSnapshot, OrderMoneySnapshot moneySnapshot,
                    OrderDeliverySlot deliverySlot, OrderCancellation cancellation, OrderFulfilment fulfilment) {

    /** Without fulfilment timestamps (every order before fulfilment statuses existed). */
    public Order(OrderId orderId, String customerId, String quoteId, OrderStatus status, PaymentMethod paymentMethod,
                 long version, String addressId, long addressVersion, OrderAddressSnapshot addressSnapshot,
                 List<OrderLine> lines, int itemCount, long subtotalPaise, String currency, String reservationId,
                 ConfirmedPaymentCondition confirmedPaymentCondition, Instant createdAt, Instant confirmedAt,
                 Instant updatedAt, OrderBenefitSnapshot benefitSnapshot, OrderMoneySnapshot moneySnapshot,
                 OrderDeliverySlot deliverySlot, OrderCancellation cancellation) {
        this(orderId, customerId, quoteId, status, paymentMethod, version, addressId, addressVersion, addressSnapshot, lines,
                itemCount, subtotalPaise, currency, reservationId, confirmedPaymentCondition, createdAt, confirmedAt, updatedAt,
                benefitSnapshot, moneySnapshot, deliverySlot, cancellation, OrderFulfilment.NONE);
    }

    /** An order that was never cancelled and carries the given slot (or none). */
    public Order(OrderId orderId, String customerId, String quoteId, OrderStatus status, PaymentMethod paymentMethod,
                 long version, String addressId, long addressVersion, OrderAddressSnapshot addressSnapshot,
                 List<OrderLine> lines, int itemCount, long subtotalPaise, String currency, String reservationId,
                 ConfirmedPaymentCondition confirmedPaymentCondition, Instant createdAt, Instant confirmedAt,
                 Instant updatedAt, OrderBenefitSnapshot benefitSnapshot, OrderMoneySnapshot moneySnapshot,
                 OrderDeliverySlot deliverySlot) {
        this(orderId, customerId, quoteId, status, paymentMethod, version, addressId, addressVersion, addressSnapshot, lines,
                itemCount, subtotalPaise, currency, reservationId, confirmedPaymentCondition, createdAt, confirmedAt, updatedAt,
                benefitSnapshot, moneySnapshot, deliverySlot, null);
    }

    /** An order without a delivery slot (every order placed before slots existed, and any placement that chose none). */
    public Order(OrderId orderId, String customerId, String quoteId, OrderStatus status, PaymentMethod paymentMethod,
                 long version, String addressId, long addressVersion, OrderAddressSnapshot addressSnapshot,
                 List<OrderLine> lines, int itemCount, long subtotalPaise, String currency, String reservationId,
                 ConfirmedPaymentCondition confirmedPaymentCondition, Instant createdAt, Instant confirmedAt,
                 Instant updatedAt, OrderBenefitSnapshot benefitSnapshot, OrderMoneySnapshot moneySnapshot) {
        this(orderId, customerId, quoteId, status, paymentMethod, version, addressId, addressVersion, addressSnapshot, lines,
                itemCount, subtotalPaise, currency, reservationId, confirmedPaymentCondition, createdAt, confirmedAt, updatedAt,
                benefitSnapshot, moneySnapshot, null, null);
    }

    public Order {
        if (fulfilment == null) {
            fulfilment = OrderFulfilment.NONE;
        }
        if (orderId == null) {
            throw new IllegalArgumentException("orderId required");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId required");
        }
        if (!CheckoutQuoteId.isValid(quoteId)) {
            throw new IllegalArgumentException("invalid quoteId shape");
        }
        if (status == null) {
            throw new IllegalArgumentException("status required");
        }
        if (paymentMethod == null) {
            throw new IllegalArgumentException("paymentMethod required");
        }
        if (addressId == null) {
            throw new IllegalArgumentException("addressId required");
        }
        new AddressId(addressId); // throws IllegalArgumentException on an invalid shape
        if (addressVersion < 0) {
            throw new IllegalArgumentException("addressVersion must be >= 0: " + addressVersion);
        }
        if (addressSnapshot == null) {
            throw new IllegalArgumentException("addressSnapshot required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines must be non-empty");
        }
        Set<String> seen = new HashSet<>();
        long computedSubtotal = 0;
        int computedItemCount = 0;
        for (OrderLine l : lines) {
            if (!seen.add(l.skuId())) {
                throw new IllegalArgumentException("duplicate skuId in order: " + l.skuId());
            }
            computedSubtotal = Math.addExact(computedSubtotal, l.lineTotalPaise());
            computedItemCount = Math.addExact(computedItemCount, l.quantity());
        }
        lines = List.copyOf(lines);
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be >= 1: " + itemCount);
        }
        if (itemCount != computedItemCount) {
            throw new IllegalArgumentException("itemCount does not equal the sum of line quantities");
        }
        if (subtotalPaise < 0) {
            throw new IllegalArgumentException("subtotalPaise must be >= 0: " + subtotalPaise);
        }
        if (subtotalPaise != computedSubtotal) {
            throw new IllegalArgumentException("subtotalPaise does not equal the sum of line totals");
        }
        if (!"INR".equals(currency)) {
            throw new IllegalArgumentException("currency must be INR");
        }
        if (!InventoryReservationId.isValid(reservationId)) {
            throw new IllegalArgumentException("invalid reservationId shape");
        }
        // benefitSnapshot: null ONLY for a legacy Order created before the Benefits-aware placement (never "no
        // benefit"). When present it must be about THIS Order's canonical merchandise subtotal.
        if (benefitSnapshot != null && benefitSnapshot.eligibleSubtotalPaise() != subtotalPaise) {
            throw new IllegalArgumentException("benefit snapshot eligibleSubtotalPaise does not equal the order subtotal");
        }
        // moneySnapshot: null ONLY for a legacy Order created before the V1 money model (never "payable = subtotal").
        // When present it must be about THIS Order's canonical subtotal and its authoritative Benefits discount: the
        // Order-side benefit snapshot is mandatory beside it (a discount cannot be verified without it), and the money
        // discount is that snapshot's discount (0 for no benefit), never an independent value.
        if (moneySnapshot != null) {
            if (benefitSnapshot == null) {
                throw new IllegalArgumentException("a money snapshot requires the benefit snapshot it was derived from");
            }
            if (moneySnapshot.merchandiseSubtotalPaise() != subtotalPaise) {
                throw new IllegalArgumentException("money snapshot merchandise subtotal does not equal the order subtotal");
            }
            long benefitDiscount = benefitSnapshot instanceof OrderBenefitSnapshot.Applied a ? a.discountPaise() : 0L;
            if (moneySnapshot.benefitDiscountPaise() != benefitDiscount) {
                throw new IllegalArgumentException("money snapshot benefit discount does not equal the benefit snapshot's");
            }
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("createdAt/updatedAt required");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        if ((status == OrderStatus.CANCELLED) != (cancellation != null)) {
            throw new IllegalArgumentException("a cancellation is present exactly on a CANCELLED order");
        }
        switch (status) {
            case CANCELLED -> {
                long expected = fulfilment.outForDeliveryAt() == null ? 3 : 4;
                if (version != expected) {
                    throw new IllegalArgumentException("CANCELLED order must be version " + expected + ": " + version);
                }
                if (fulfilment.deliveredAt() != null) {
                    throw new IllegalArgumentException("a delivered order is never cancelled");
                }
                if (paymentMethod != PaymentMethod.COD || confirmedAt == null || confirmedPaymentCondition != ConfirmedPaymentCondition.COD_DUE) {
                    throw new IllegalArgumentException("CANCELLED order must have been a confirmed COD order");
                }
                if (cancellation.cancelledAt().isBefore(confirmedAt)) {
                    throw new IllegalArgumentException("cancelledAt must not precede confirmedAt");
                }
                if (updatedAt.isBefore(cancellation.cancelledAt())) {
                    throw new IllegalArgumentException("updatedAt must not precede cancelledAt");
                }
            }
            case OUT_FOR_DELIVERY, DELIVERED -> {
                boolean delivered = status == OrderStatus.DELIVERED;
                if (version != (delivered ? 4 : 3)) {
                    throw new IllegalArgumentException(status + " order must be version " + (delivered ? 4 : 3) + ": " + version);
                }
                if (paymentMethod != PaymentMethod.COD || confirmedAt == null || confirmedPaymentCondition != ConfirmedPaymentCondition.COD_DUE) {
                    throw new IllegalArgumentException(status + " order must have been a confirmed COD order");
                }
                if (fulfilment.outForDeliveryAt() == null || fulfilment.outForDeliveryAt().isBefore(confirmedAt)
                        || (delivered != (fulfilment.deliveredAt() != null))) {
                    throw new IllegalArgumentException(status + " order has inconsistent fulfilment timestamps");
                }
                Instant last = delivered ? fulfilment.deliveredAt() : fulfilment.outForDeliveryAt();
                if (updatedAt.isBefore(last)) {
                    throw new IllegalArgumentException("updatedAt must not precede the last fulfilment step");
                }
            }
            case CREATED -> {
                if (version != 1) {
                    throw new IllegalArgumentException("CREATED order must be version 1: " + version);
                }
                if (confirmedAt != null || confirmedPaymentCondition != null || fulfilment.outForDeliveryAt() != null) {
                    throw new IllegalArgumentException("CREATED order must not carry confirmation or fulfilment fields");
                }
            }
            case CONFIRMED -> {
                if (fulfilment.outForDeliveryAt() != null) {
                    throw new IllegalArgumentException("CONFIRMED order carries no fulfilment step");
                }
                if (version != 2) {
                    throw new IllegalArgumentException("CONFIRMED order must be version 2: " + version);
                }
                if (paymentMethod != PaymentMethod.COD) {
                    throw new IllegalArgumentException("CONFIRMED order requires paymentMethod COD");
                }
                if (confirmedPaymentCondition != ConfirmedPaymentCondition.COD_DUE) {
                    throw new IllegalArgumentException("CONFIRMED COD order requires condition COD_DUE");
                }
                if (confirmedAt == null) {
                    throw new IllegalArgumentException("CONFIRMED order requires confirmedAt");
                }
                if (confirmedAt.isBefore(createdAt)) {
                    throw new IllegalArgumentException("confirmedAt must not precede createdAt");
                }
                if (updatedAt.isBefore(confirmedAt)) {
                    throw new IllegalArgumentException("updatedAt must not precede confirmedAt");
                }
            }
        }
    }

    /** The public-safe projection of the stored AUTHORITATIVE money; EMPTY for a legacy Order (never a zero payable). */
    java.util.Optional<OrderMoneyView> moneyView() {
        return moneySnapshot == null ? java.util.Optional.empty()
                : java.util.Optional.of(OrderMoneyView.from(moneySnapshot));
    }
}
