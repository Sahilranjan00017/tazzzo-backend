package com.tazzzo.catalog.datastore;

/**
 * One datastore-contract finding. {@code code} is a stable machine-readable token; {@code message} is fixed text
 * that names OPTIONS and collections, never a connection string, host, user name, password or document content.
 */
public record Violation(String code, String message) {

    @Override
    public String toString() {
        return code + ": " + message;
    }
}
