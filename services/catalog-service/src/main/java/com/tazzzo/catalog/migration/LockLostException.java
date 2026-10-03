package com.tazzzo.catalog.migration;

/** The runner no longer holds the migration lock (lease expired or taken over); it must stop and record nothing. */
public class LockLostException extends MigrationException {
    public LockLostException(String message) {
        super(message);
    }
}
