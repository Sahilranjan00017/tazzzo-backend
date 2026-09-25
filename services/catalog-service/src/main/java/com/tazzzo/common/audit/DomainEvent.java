package com.tazzzo.common.audit;

import java.util.Map;

/**
 * Neutral audit event for aggregates that are NOT products (PR-06, STEP 21).
 *
 * <p>Pricing/Inventory/Media reused the product-coupled {@code EventPayload}/{@code product_events}
 * rail because their launch identity is a product/SKU. Serviceability is the first domain with NO
 * product identity, and faking {@code productId} with a service-area id would poison product audit
 * semantics. This is the smallest neutral alternative: an explicit aggregate type + id. It is an
 * AUDIT primitive only — not an event bus, not an outbox consumer contract.
 */
public record DomainEvent(String aggregateType, String aggregateId, String type,
                          Map<String, Object> detail) {

    public DomainEvent {
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType required");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type required");
        }
        if (detail == null) detail = Map.of();
    }
}
