package com.tazzzo.customer.cart;

import java.time.Instant;
import java.util.List;

/**
 * PR-12C — the persisted cart INTENT and nothing else: SKU + desired quantity + timestamps + a
 * monotonic cart version. No price, title, stock or routing is ever part of it.
 *
 * @param version   logical version; 0 only for a customer who has never had a persisted cart
 * @param expiresAt when the CURRENT contents expire; null when the cart is empty/never persisted
 * @param expiredCleared true when producing this state cleared an expired cart (metrics only)
 */
public record CartState(long version, List<Line> lines, Instant expiresAt, boolean expiredCleared) {

    public record Line(String skuId, int quantity, Instant addedAt, Instant updatedAt) {
    }

    public static CartState empty(long version) {
        return new CartState(version, List.of(), null, false);
    }
}
