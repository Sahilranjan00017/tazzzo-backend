package com.tazzzo.catalog.tx;

public class CasConflictException extends RuntimeException {
    public CasConflictException(String message) { super(message); }
}
