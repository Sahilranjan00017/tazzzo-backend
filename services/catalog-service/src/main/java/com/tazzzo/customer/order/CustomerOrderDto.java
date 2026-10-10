package com.tazzzo.customer.order;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * PR-15A-2 — the customer-safe Order. Built ONLY from the Order's own immutable stored snapshots —
 * never from current Product/Address/Pricing. A COMMITTED Order is always {@code CONFIRMED} with
 * {@code paymentCondition = COD_DUE}: confirmed, payment owed on delivery, nothing collected.
 *
 * <p>Deliberately NOT exposed: customerId, quoteId, reservationId, addressId, addressVersion, the Order
 * {@code version}, any fulfillment/routing identity, coordinates, or any Mongo/internal field.
 */
public record CustomerOrderDto(String orderId, String status, String paymentMethod,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String paymentCondition,
                               List<Item> items, int itemCount, long subtotalPaise, String currency,
                               DeliveryAddress deliveryAddress, String createdAt, String confirmedAt,
                               @JsonInclude(JsonInclude.Include.NON_NULL) OrderMoney money,
                               @JsonInclude(JsonInclude.Include.NON_NULL) DeliverySlot deliverySlot,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String cancelledAt,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String outForDeliveryAt,
                               @JsonInclude(JsonInclude.Include.NON_NULL) String deliveredAt, String requestId) {

    /** The delivery window the customer chose: only what they need to see (never the area, the window id or any capacity). */
    public record DeliverySlot(String slotId, String label, String startsAt, String endsAt) { }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CustomerOrderItem")
    public record Item(String skuId, String title, String brandCode, int quantity, long unitPricePaise,
                       long lineTotalPaise) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliveryAddress(String label, String recipientName, String recipientPhone, String addressLine1,
                                  String addressLine2, String landmark, String city, String state,
                                  String postalCode) {
    }

    /**
     * The AUTHORITATIVE commerce money of this Order, projected from the persisted Order money (through the public-safe
     * {@link OrderMoneyView}) and never recomputed here: {@code payablePaise = merchandiseSubtotalPaise -
     * benefitDiscountPaise}. The Order computes it independently at placement (revalidating Pricing, Membership and
     * Benefits); it may differ from the quote's advisory {@code moneyPreview}, and the Order's value is the one that holds.
     * {@code payablePaise} is the commerce amount owed (the Order is {@code COD_DUE}: due on delivery); it is not a statement
     * that anything was paid, authorized or captured, and {@code 0} is valid (a full discount: nothing is due). ABSENT on an
     * Order created before the money model, which is NEVER a zero payable.
     */
    public record OrderMoney(long merchandiseSubtotalPaise, long benefitDiscountPaise, long payablePaise) {
        public OrderMoney {
            if (merchandiseSubtotalPaise < 0 || benefitDiscountPaise < 0 || benefitDiscountPaise > merchandiseSubtotalPaise
                    || payablePaise != merchandiseSubtotalPaise - benefitDiscountPaise) {
                throw new IllegalArgumentException("inconsistent order money");
            }
        }

        static OrderMoney of(OrderMoneyView v) {
            return new OrderMoney(v.merchandiseSubtotalPaise(), v.benefitDiscountPaise(), v.payablePaise());
        }
    }

    /** Only a {@code CONFIRMED} Order is ever customer-visible ({@code OrderService.getOrder} and
     *  {@code placeCodOrder} guarantee it); anything else here is a programming defect, never rendered. */
    static CustomerOrderDto of(Order o, String requestId) {
        if (o.status() == OrderStatus.CREATED || o.confirmedAt() == null || o.confirmedPaymentCondition() == null) {
            throw new IllegalStateException("an internal CREATED order is never customer-visible");
        }
        boolean cancelled = o.status() == OrderStatus.CANCELLED;
        OrderAddressSnapshot a = o.addressSnapshot();
        return new CustomerOrderDto(o.orderId().value(), o.status().name(), o.paymentMethod().name(),
                cancelled ? null : o.confirmedPaymentCondition().name(),   // nothing is due on a cancelled order
                o.lines().stream().map(l -> new Item(l.skuId(), l.title(), l.brandCode(), l.quantity(),
                        l.unitPricePaise(), l.lineTotalPaise())).toList(),
                o.itemCount(), o.subtotalPaise(), o.currency(),
                new DeliveryAddress(a.label(), a.recipientName(), a.recipientPhone(), a.addressLine1(),
                        a.addressLine2(), a.landmark(), a.city(), a.state(), a.postalCode()),
                o.createdAt().toString(), o.confirmedAt().toString(),
                o.moneyView().map(OrderMoney::of).orElse(null),
                o.deliverySlot() == null ? null : new DeliverySlot(o.deliverySlot().slotId(), o.deliverySlot().label(),
                        o.deliverySlot().startsAt().toString(), o.deliverySlot().endsAt().toString()),
                cancelled ? o.cancellation().cancelledAt().toString() : null,
                o.fulfilment().outForDeliveryAt() == null ? null : o.fulfilment().outForDeliveryAt().toString(),
                o.fulfilment().deliveredAt() == null ? null : o.fulfilment().deliveredAt().toString(), requestId);
    }

    /** One row of the order history: just what a list needs, never the address or the lines. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String orderId, String status, String paymentMethod, int itemCount, long subtotalPaise,
                          Long payablePaise, String createdAt, DeliverySlot deliverySlot, String cancelledAt) {

        static Summary of(Order o) {
            boolean cancelled = o.status() == OrderStatus.CANCELLED;
            return new Summary(o.orderId().value(), o.status().name(), o.paymentMethod().name(), o.itemCount(),
                    o.subtotalPaise(), o.moneyView().map(OrderMoneyView::payablePaise).orElse(null), o.createdAt().toString(),
                    o.deliverySlot() == null ? null : new DeliverySlot(o.deliverySlot().slotId(), o.deliverySlot().label(),
                            o.deliverySlot().startsAt().toString(), o.deliverySlot().endsAt().toString()),
                    cancelled ? o.cancellation().cancelledAt().toString() : null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<Summary> items, String nextCursor, String requestId) { }
}
