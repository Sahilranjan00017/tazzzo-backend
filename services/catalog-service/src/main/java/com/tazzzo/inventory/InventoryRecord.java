package com.tazzzo.inventory;

import com.tazzzo.commerce.contract.StockState;

/**
 * Canonical inventory state for one {@link InventoryKey} (ADR-004). Quantities are integer unit
 * counts ({@code long}) — the launch catalogue holds SKU units, never fractional grams/ml.
 *
 * <p><b>{@code available} and {@code stockState} are DERIVED, never persisted.</b> Persisting
 * three independently mutable counters invites drift; deriving from the two authoritative
 * counters ({@code onHand}, {@code reserved}) makes drift impossible, and Mongo single-document
 * atomicity keeps both counters consistent under every mutation.
 *
 * <p>{@code stockState} here is the state OF AN EXISTING ROW — it is never
 * {@link StockState#UNKNOWN}. UNKNOWN belongs to read/composition boundaries (missing row,
 * inventory unreachable), which {@link InventoryLookup} models separately: MISSING is a
 * data-quality signal, not zero stock.
 */
public record InventoryRecord(
        String skuId,
        String fulfillmentLocationId,
        long onHand,
        long reserved,
        long lowStockThreshold,
        long maxPurchasable,
        long version,
        boolean active
) {
    public InventoryRecord {
        new InventoryKey(skuId, fulfillmentLocationId); // identity validation
        if (onHand < 0) throw new IllegalArgumentException("onHand must be >= 0: " + onHand);
        if (reserved < 0) throw new IllegalArgumentException("reserved must be >= 0: " + reserved);
        if (reserved > onHand) {
            // Strict for PR-04. A documented exceptional reconciliation state (short receipt
            // against live reservations) is future work — not silently permitted here.
            throw new IllegalArgumentException("reserved (" + reserved + ") must be <= onHand (" + onHand + ")");
        }
        if (lowStockThreshold < 0) throw new IllegalArgumentException("lowStockThreshold must be >= 0");
        if (maxPurchasable < 0) throw new IllegalArgumentException("maxPurchasable must be >= 0");
        if (version < 1) throw new IllegalArgumentException("version must be positive: " + version);
    }

    /** The only stock truth: what is on hand minus what is promised. Never negative by invariant. */
    public long available() {
        return onHand - reserved;
    }

    /**
     * FROZEN SEMANTICS (PR-04 review): {@code maxPurchasable} is a PERSISTED, source-provided,
     * inventory-side static cap per row — never derived, and never the final ProductCard
     * {@code maxOrderQuantity} (that is composed later as
     * {@code min(catalog policy, effectivePurchasableQuantity())} in commerce.read). The
     * inventory-side EFFECTIVE cap can never exceed what stock supports:
     * {@code min(maxPurchasable, available)}.
     */
    public long effectivePurchasableQuantity() {
        return Math.min(maxPurchasable, available());
    }

    /**
     * Canonical derivation (Phase 3.1 STEP 7):
     * available == 0 → OUT_OF_STOCK; 0 < available <= threshold → LOW_STOCK; else IN_STOCK.
     */
    public StockState stockState() {
        long available = available();
        if (available == 0) return StockState.OUT_OF_STOCK;
        if (available <= lowStockThreshold) return StockState.LOW_STOCK;
        return StockState.IN_STOCK;
    }
}
