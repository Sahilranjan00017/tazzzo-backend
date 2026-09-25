package com.tazzzo.serviceability;

/**
 * One fulfillment candidate for a service area (PR-06, STEP 8). {@code fulfillmentLocationId}
 * is INTERNAL — it must never appear in a public DTO, and clients can never submit one.
 * {@code priority} drives deterministic routing: the ACTIVE route with the LOWEST priority
 * number wins; priorities are unique within an area, so no tie-break is ever needed and no
 * resolution can depend on Mongo natural order.
 */
public record ServiceabilityRoute(String fulfillmentLocationId, int priority, boolean active) {

    static final int MAX_ID = 128;

    public ServiceabilityRoute {
        if (fulfillmentLocationId == null || fulfillmentLocationId.isBlank()
                || fulfillmentLocationId.length() > MAX_ID
                || !fulfillmentLocationId.equals(fulfillmentLocationId.trim())
                || fulfillmentLocationId.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException(
                    "fulfillmentLocationId required: non-blank, trimmed, no control chars, max " + MAX_ID);
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be >= 0: " + priority);
        }
    }
}
