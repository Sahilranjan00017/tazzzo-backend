package com.tazzzo.catalog.events;

import com.tazzzo.common.audit.Actor;

import java.util.Map;

/**
 * C-4: every mutation through the write path requires an event payload at compile time.
 * The event is appended to product_events BEFORE the state write (C-3), same session.
 *
 * <p>{@code actor} is WHO performed the mutation ({@link Actor}): an admin request's service-account or human principal, or
 * a {@code SYSTEM} worker. It is persisted on the event row in the same transaction. It is {@code null} only for a mutation
 * path that has no audited caller yet (an unattributed event); a {@code null} actor is never replaced by a guess.
 */
public record EventPayload(String type, String productId, Map<String, Object> detail, Actor actor) {

    public EventPayload {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("event type required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
        if (detail == null) detail = Map.of();
    }

    /** An UNATTRIBUTED event: for mutation paths that have no audited caller yet (never for an admin request). */
    public EventPayload(String type, String productId, Map<String, Object> detail) {
        this(type, productId, detail, null);
    }
}
