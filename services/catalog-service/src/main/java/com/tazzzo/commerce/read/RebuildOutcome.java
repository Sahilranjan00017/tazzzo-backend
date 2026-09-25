package com.tazzzo.commerce.read;

/** Outcome of one projection rebuild — returned for tests/observability, logged per hook. */
public enum RebuildOutcome {
    /** No projection existed; one was created (projectionVersion 1). */
    CREATED,
    /** Business content changed; row CAS-updated (projectionVersion +1). */
    UPDATED,
    /** Sources produced identical business content; NOTHING was written (no version churn). */
    NOOP,
    /** Catalog no longer eligible; the derived row was deleted (sources untouched). */
    REMOVED,
    /** Catalog not eligible and no row existed; nothing to do. */
    MISSING
}
