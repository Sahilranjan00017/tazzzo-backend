package com.tazzzo.customer.checkout;

import java.time.Instant;
import java.util.List;

/**
 * PR-13A — the immutable checkout quote: the validated snapshot at {@code createdAt}. It is NOT a
 * reservation of stock and NOT a permanent price lock; it is only valid until {@code expiresAt} and
 * a future Order must revalidate stock and quote validity. Carries no internal routing identity.
 */
public record CheckoutQuote(String quoteId, long cartVersion, String addressId, List<Line> lines, int itemCount,
                            long subtotalPaise, String currency, Instant createdAt, Instant expiresAt) {

    public record Line(String skuId, int quantity, long unitPricePaise, long lineTotalPaise) {
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
