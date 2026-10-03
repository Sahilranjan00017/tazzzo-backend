package com.tazzzo.admin.audit;

import java.util.List;
import java.util.Optional;

/** One page, newest first; {@code nextCursor} is present exactly when more matching events exist after this page. */
public record AuditEventPage(List<AuditEventRecord> items, Optional<String> nextCursor) {

    public AuditEventPage {
        items = List.copyOf(items);
    }
}
