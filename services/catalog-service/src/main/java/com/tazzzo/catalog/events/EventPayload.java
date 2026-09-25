package com.tazzzo.catalog.events;

import java.util.Map;

/**
 * C-4: every mutation through the write path requires an event payload at compile time.
 * The event is appended to product_events BEFORE the state write (C-3), same session.
 */
public record EventPayload(String type, String productId, Map<String, Object> detail) {

    public EventPayload {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
        if (detail == null) detail = Map.of();
    }
}
