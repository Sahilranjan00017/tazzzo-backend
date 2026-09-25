package com.tazzzo.inventory;

/** An update addressed an inventory row that does not exist. */
public class InventoryNotFoundException extends InventoryException {
    public InventoryNotFoundException(String message) { super(message); }
}
