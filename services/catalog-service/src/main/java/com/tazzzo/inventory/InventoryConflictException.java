package com.tazzzo.inventory;

/** Optimistic-concurrency conflict: expected version did not match, or duplicate create. */
public class InventoryConflictException extends InventoryException {
    public InventoryConflictException(String message) { super(message); }
}
