package com.tazzzo.catalog.tx;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String m) { super("no such product: " + m); }
}
