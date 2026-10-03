package com.tazzzo.catalog.datastore;

import java.util.List;
import java.util.stream.Collectors;

/** Startup refused because the datastore contract is not satisfied. The message lists finding codes, never secrets. */
public class DatastoreContractException extends IllegalStateException {

    private final List<Violation> violations;

    public DatastoreContractException(String context, List<Violation> violations) {
        super("datastore contract not satisfied (" + context + ") - refusing to start: "
                + violations.stream().map(Violation::toString).collect(Collectors.joining("; "))
                + ". See docs/database/DATABASE_STAGING_RUNBOOK.md.");
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
