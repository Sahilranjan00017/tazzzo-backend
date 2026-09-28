package com.tazzzo.customer.checkout;

import java.util.List;

/**
 * PR-13A — the public quote. A validated snapshot at {@code createdAt}: NOT a stock reservation and
 * NOT a final payable total (no delivery/platform fee, tax, tip or discount). Never exposes a
 * fulfillment/routing identity.
 */
public record CheckoutQuoteDto(String quoteId, long cartVersion, String addressId, List<Item> items, int itemCount,
                               int distinctItemCount, long subtotalPaise, String currency, String createdAt,
                               String expiresAt, String requestId) {

    public record Item(String skuId, int quantity, long unitPricePaise, long lineTotalPaise) {
    }

    static CheckoutQuoteDto of(CheckoutQuote q, String requestId) {
        return new CheckoutQuoteDto(q.quoteId(), q.cartVersion(), q.addressId(),
                q.lines().stream().map(l -> new Item(l.skuId(), l.quantity(), l.unitPricePaise(), l.lineTotalPaise()))
                        .toList(),
                q.itemCount(), q.lines().size(), q.subtotalPaise(), q.currency(), q.createdAt().toString(),
                q.expiresAt().toString(), requestId);
    }
}
