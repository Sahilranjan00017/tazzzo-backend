package com.tazzzo.customer.order;

import java.time.Instant;

/** Who cancelled a confirmed order, when, and the closed reason code. Never free text. */
public record OrderCancellation(Instant cancelledAt, CancelledBy cancelledBy, String reasonCode) {

    public enum CancelledBy { CUSTOMER, STAFF, SYSTEM }

    /** The closed set of reason codes a customer may give. */
    public static final java.util.Set<String> CUSTOMER_REASONS = java.util.Set.of("CHANGED_MIND", "ORDERED_BY_MISTAKE", "OTHER");

    public OrderCancellation {
        if (cancelledAt == null || cancelledBy == null || reasonCode == null || !reasonCode.matches("[A-Z][A-Z_]{1,31}")) {
            throw new IllegalArgumentException("invalid order cancellation");
        }
    }
}
