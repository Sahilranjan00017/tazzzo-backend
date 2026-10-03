package com.tazzzo.catalog.migration;

/** A migration cannot proceed safely (conflict, lost lock, refused target). Messages never contain secrets. */
public class MigrationException extends RuntimeException {
    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
