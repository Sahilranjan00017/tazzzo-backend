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
 *
 * <p><b>Identity semantics (PR-06 review, STEP 4):</b> {@code aggregateType} names the KIND of
 * the versioned persistence aggregate and {@code aggregateId} is that aggregate's STABLE identity
 * — i.e. the document the CAS version lives on. Human-facing grouping labels (like a shared
 * service-area name) are NOT aggregate identity and belong in {@code detail}.
 *
 * <p><b>Immutability (STEP 5):</b> {@code detail} is defensively copied at construction
 * ({@link Map#copyOf}), so a caller mutating its own map after constructing the event can never
 * alter what gets audited. Values are expected to be flat/simple; nested mutable structures are
 * not part of this contract.
 */
public record DomainEvent(String aggregateType, String aggregateId, String type,
                          Map<String, Object> detail) {

    static final int MAX_FIELD = 200;

    public DomainEvent {
        aggregateType = requireBounded(aggregateType, "aggregateType");
        aggregateId = requireBounded(aggregateId, "aggregateId");
        type = requireBounded(type, "event type");
        detail = (detail == null) ? Map.of() : Map.copyOf(detail);
    }

    private static String requireBounded(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_FIELD
                || value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException(
                    name + " required: non-blank, no control chars, max " + MAX_FIELD);
        }
        return value;
    }
}
