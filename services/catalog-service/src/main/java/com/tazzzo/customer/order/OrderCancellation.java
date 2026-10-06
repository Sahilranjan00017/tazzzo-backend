package com.tazzzo.customer.order;

import java.time.Instant;

/** Who cancelled a confirmed order, when, and the closed reason code. Never free text. */
public record OrderCancellation(Instant cancelledAt, CancelledBy cancelledBy, String reasonCode) {

    public enum CancelledBy { CUSTOMER, STAFF, SYSTEM }

    /** The closed set of reason codes a customer may give. */
    public static final java.util.Set<String> CUSTOMER_REASONS = java.util.Set.of("CHANGED_MIND", "ORDERED_BY_MISTAKE", "OTHER");

    /** The closed set of reason codes staff may give. */
    public static final java.util.Set<String> STAFF_REASONS = java.util.Set.of("OUT_OF_STOCK", "CUSTOMER_UNREACHABLE",
            "DELIVERY_FAILED", "CUSTOMER_REQUEST", "ADDRESS_UNSERVICEABLE", "OTHER");

    public OrderCancellation {
        if (cancelledAt == null || cancelledBy == null || reasonCode == null || !reasonCode.matches("[A-Z][A-Z_]{1,31}")) {
            throw new IllegalArgumentException("invalid order cancellation");
        }
    }
}
