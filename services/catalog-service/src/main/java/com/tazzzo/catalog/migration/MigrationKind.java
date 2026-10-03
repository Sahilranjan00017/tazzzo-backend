package com.tazzzo.catalog.migration;

/**
 * What a migration does (R5). Runtime verification is NOT a migration and is never listed here.
 * <ul>
 *   <li>{@link #SCHEMA}: collections, indexes, validators — structural evolution.</li>
 *   <li>{@link #REFERENCE_INIT}: insert-if-absent creation of known initial reference data.</li>
 *   <li>{@link #DATA}: rewriting existing persisted data. Never runs without explicit approval.</li>
 * </ul>
 */
public enum MigrationKind {
    SCHEMA, REFERENCE_INIT, DATA
}
