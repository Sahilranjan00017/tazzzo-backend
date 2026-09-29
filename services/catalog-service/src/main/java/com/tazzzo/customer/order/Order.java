package com.tazzzo.customer.order;

import com.tazzzo.customer.address.AddressId;
import com.tazzzo.customer.checkout.CheckoutQuoteId;
import com.tazzzo.inventory.InventoryReservationId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PR-14B — the immutable Order Foundation record: the durable result of one successful
 * {@code OrderService.createOrder} transaction. Every value carried here was validated/read INSIDE
 * that SAME transaction — never a pre-transaction snapshot, never re-derived afterward. The
 * compact constructor validates every invariant a caller relies on and fails LOUD (never
 * normalizes), the same convention {@code CheckoutQuote}/{@code InventoryReservation} already
 * established, including when reconstructing from a corrupt Mongo document.
 *
 * <p>Deliberately excludes {@code discountPaise}/{@code taxPaise}/{@code feePaise} and any payment
 * field: Membership/Benefits/Promotion/Payment do not exist yet in this codebase, and a
 * placeholder-zero field would misrepresent "this concept has been designed for" when it has only
 * been left a gap for. See {@code OrderService}'s class-level documentation for the ratified
 * forward-compatible money model those future PRs will extend into.
 */
public record Order(OrderId orderId, String customerId, String quoteId, OrderStatus status, String addressId,
                    long addressVersion, OrderAddressSnapshot addressSnapshot, List<OrderLine> lines, int itemCount,
                    long subtotalPaise, String currency, String reservationId, Instant createdAt,
                    Instant updatedAt) {

    public Order {
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
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("createdAt/updatedAt required");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }
}
