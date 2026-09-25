package com.tazzzo.inventory;

/** Base for typed inventory failures. Messages are internal; never surfaced to public clients. */
public abstract class InventoryException extends RuntimeException {
    protected InventoryException(String message) { super(message); }
}
