package com.tazzzo.catalog.migration;

/** The migration target is ambiguous or not permitted for the requested mode. Nothing was mutated. */
public class TargetRefusedException extends MigrationException {
    public TargetRefusedException(String message) {
        super(message);
    }
}
