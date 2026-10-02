package com.tazzzo.common.audit;

import org.bson.Document;

import java.util.Optional;
import java.util.Set;

/**
 * The persisted shape of an {@link Actor}: ONE nested {@code actor} document on an event row.
 * <pre>
 *   actor: { type, id, credential_id?, request_id? }
 * </pre>
 * Reading is STRICT: an explicitly present actor that is not a document, has a foreign key, a missing/blank/non-string
 * field, an unknown type or violates an {@link Actor} invariant fails loud. ABSENCE of the whole field is a historical
 * (pre-attribution) or deliberately unattributed event and is returned as {@link Optional#empty()}; it is never turned
 * into a SYSTEM, service or human actor.
 */
public final class ActorDocuments {

    public static final String FIELD = "actor";

    private static final Set<String> KEYS = Set.of("type", "id", "credential_id", "request_id");

    private ActorDocuments() {
    }

    public static Document toDocument(Actor actor) {
        Document d = new Document("type", actor.type().name()).append("id", actor.id());
        if (actor.credentialId() != null) {
            d.append("credential_id", actor.credentialId());
        }
        if (actor.requestId() != null) {
            d.append("request_id", actor.requestId());
        }
        return d;
    }

    /** Appends {@code actor} to an event document when present; an absent actor leaves the event unattributed. */
    public static Document appendTo(Document eventDoc, Actor actor) {
        if (actor != null) {
            eventDoc.append(FIELD, toDocument(actor));
        }
        return eventDoc;
    }

    /** The actor of an event document: empty when the field is ABSENT; a present field is strictly parsed. */
    public static Optional<Actor> fromEvent(Document eventDoc) {
        return eventDoc.containsKey(FIELD) ? Optional.of(fromDocument(eventDoc.get(FIELD))) : Optional.empty();
    }

    /** {@code raw} is the PRESENT value of the {@code actor} field (an explicit null is malformed too). */
    public static Actor fromDocument(Object raw) {
        if (!(raw instanceof Document d)) {
            throw new IllegalArgumentException("event actor must be a document");
        }
        if (!KEYS.containsAll(d.keySet())) {
            throw new IllegalArgumentException("event actor has foreign fields");
        }
        ActorType type;
        try {
            type = ActorType.valueOf(requireString(d, "type"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("event actor has an unknown type");
        }
        return new Actor(type, requireString(d, "id"), optionalString(d, "credential_id"),
                optionalString(d, "request_id"));
    }

    private static String requireString(Document d, String field) {
        if (!(d.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("event actor field missing/invalid: " + field);
        }
        return s;
    }

    private static String optionalString(Document d, String field) {
        return d.containsKey(field) ? requireString(d, field) : null;
    }
}
