package com.tazzzo.customer.cart;

import java.time.Instant;
import java.util.List;

/**
 * PR-12C — the persisted cart INTENT: SKU + desired quantity + timestamps + a monotonic cart version. No title, stock
 * or routing is ever part of it. The one price-shaped field, {@link Line#unitPricePaiseAtUpdate}, is an OBSERVATION
 * recorded when the line was last set: it is used only to flag {@link CartIssue#PRICE_CHANGED} for an aging cart and
 * is never a price authority, never part of a total (totals always use the CURRENT price).
 *
 * @param version   logical version; 0 only for a customer who has never had a persisted cart
 * @param expiresAt when the CURRENT contents expire; null when the cart is empty/never persisted
 * @param expiredCleared true when producing this state cleared an expired cart (metrics only)
 * @param freshness the age band of the contents (see {@link Freshness})
 */
public record CartState(long version, List<Line> lines, Instant expiresAt, boolean expiredCleared, Freshness freshness) {

    /**
     * The cart age policy, by time since the last mutation: under 24 hours the cart is FRESH (kept, presented as
     * is); from 24 hours up to and including 7 days it is kept but must be REVALIDATED (current-state issues plus
     * {@code PRICE_CHANGED} against the price observed when each line was set); older than 7 days it expires.
     */
    public enum Freshness { FRESH, REVALIDATE }

    public CartState(long version, List<Line> lines, Instant expiresAt, boolean expiredCleared) {
        this(version, lines, expiresAt, expiredCleared, Freshness.FRESH);
    }

    /** @param unitPricePaiseAtUpdate the selling price observed when the line was last set; null when unknown */
    public record Line(String skuId, int quantity, Instant addedAt, Instant updatedAt, Long unitPricePaiseAtUpdate) {

        public Line(String skuId, int quantity, Instant addedAt, Instant updatedAt) {
            this(skuId, quantity, addedAt, updatedAt, null);
        }
    }

    public static CartState empty(long version) {
        return new CartState(version, List.of(), null, false);
    }
}
