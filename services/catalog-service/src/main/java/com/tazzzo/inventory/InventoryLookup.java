package com.tazzzo.inventory;

import java.util.Optional;

/**
 * Result of an inventory read (STEP 12/15). Distinguishes three states a composition layer must
 * treat differently:
 * <ul>
 *   <li>{@code PRESENT} — an active row exists; stock truth is {@code record.stockState()}
 *       (which may legitimately be OUT_OF_STOCK).</li>
 *   <li>{@code MISSING} — no row exists. NOT the same as zero stock: it is a data-quality
 *       signal, surfaces as StockState.UNKNOWN / not-buyable at composition.</li>
 *   <li>{@code INACTIVE} — a row exists but is switched off; not buyable regardless of counters.</li>
 * </ul>
 */
public record InventoryLookup(Status status, InventoryRecord record) {

    public enum Status { PRESENT, MISSING, INACTIVE }

    public static InventoryLookup missing() {
        return new InventoryLookup(Status.MISSING, null);
    }

    public static InventoryLookup of(Status status, InventoryRecord record) {
        return new InventoryLookup(status, record);
    }

    public boolean isPresent() {
        return status == Status.PRESENT && record != null;
    }

    public Optional<InventoryRecord> presentRecord() {
        return isPresent() ? Optional.of(record) : Optional.empty();
    }
}
