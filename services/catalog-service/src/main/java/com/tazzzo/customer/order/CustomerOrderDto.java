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
public record CustomerOrderDto(String orderId, String status, String paymentMethod, String paymentCondition,
                               List<Item> items, int itemCount, long subtotalPaise, String currency,
                               DeliveryAddress deliveryAddress, String createdAt, String confirmedAt,
                               String requestId) {

    public record Item(String skuId, String title, String brandCode, int quantity, long unitPricePaise,
                       long lineTotalPaise) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliveryAddress(String label, String recipientName, String recipientPhone, String addressLine1,
                                  String addressLine2, String landmark, String city, String state,
                                  String postalCode) {
    }

    /** Only a {@code CONFIRMED} Order is ever customer-visible ({@code OrderService.getOrder} and
     *  {@code placeCodOrder} guarantee it); anything else here is a programming defect, never rendered. */
    static CustomerOrderDto of(Order o, String requestId) {
        if (o.status() != OrderStatus.CONFIRMED || o.confirmedAt() == null
                || o.confirmedPaymentCondition() == null) {
            throw new IllegalStateException("only a CONFIRMED order is customer-visible");
        }
        OrderAddressSnapshot a = o.addressSnapshot();
        return new CustomerOrderDto(o.orderId().value(), o.status().name(), o.paymentMethod().name(),
                o.confirmedPaymentCondition().name(),
                o.lines().stream().map(l -> new Item(l.skuId(), l.title(), l.brandCode(), l.quantity(),
                        l.unitPricePaise(), l.lineTotalPaise())).toList(),
                o.itemCount(), o.subtotalPaise(), o.currency(),
                new DeliveryAddress(a.label(), a.recipientName(), a.recipientPhone(), a.addressLine1(),
                        a.addressLine2(), a.landmark(), a.city(), a.state(), a.postalCode()),
                o.createdAt().toString(), o.confirmedAt().toString(), requestId);
    }
}
