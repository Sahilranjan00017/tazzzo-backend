package com.tazzzo.inventory;

/** An inventory command violated a domain invariant (bounds, identity, reserved coverage). */
public class InvalidInventoryException extends InventoryException {
    public InvalidInventoryException(String message) { super(message); }
}
