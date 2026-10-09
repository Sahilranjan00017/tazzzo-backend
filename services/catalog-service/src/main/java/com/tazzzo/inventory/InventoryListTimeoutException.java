package com.tazzzo.inventory;

/** The admin stock list query exceeded its time bound (a sparse filter over a large collection). */
public class InventoryListTimeoutException extends RuntimeException {
    public InventoryListTimeoutException() {
        super("the stock list query took too long; narrow it with a location or retry", null, false, false);
    }
}
