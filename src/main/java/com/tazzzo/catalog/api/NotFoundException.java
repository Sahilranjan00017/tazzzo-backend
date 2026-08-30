package com.tazzzo.catalog.api;

/** Transport-level "entity not found" — distinct from domain IllegalStateException (M4). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
