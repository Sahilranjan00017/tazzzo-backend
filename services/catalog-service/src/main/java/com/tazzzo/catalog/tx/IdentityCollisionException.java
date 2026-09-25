package com.tazzzo.catalog.tx;

public class IdentityCollisionException extends RuntimeException {
    public IdentityCollisionException(String message) { super(message); }
}
