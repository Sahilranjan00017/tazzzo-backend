package com.tazzzo.catalog.migration;

import org.bson.Document;

/**
 * Outcome of {@link Migration#apply}. {@code rollbackInfo} (nullable) is whatever an operator needs to
 * reverse the change by hand (for example the previous validator options); it is stored in the history.
 */
public record ApplyResult(String note, Document rollbackInfo) {
    public static ApplyResult of(String note) {
        return new ApplyResult(note, null);
    }
}
