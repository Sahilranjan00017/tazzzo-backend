package com.tazzzo.common.audit;

/**
 * The authenticated (or system) identity that performed an audited mutation, persisted with the mutation's event in the
 * SAME transaction. A neutral value type: it depends on nothing in {@code com.tazzzo.admin} (an admin principal converts
 * INTO an actor, never the reverse), so every domain may accept and record it.
 *
 * <ul>
 *   <li>{@code type} and {@code id} are required; {@code id} is a stable, immutable identifier (never a display name);</li>
 *   <li>{@code credentialId} optionally names WHICH credential authenticated the actor — a stable, non-secret label, never
 *       token material;</li>
 *   <li>{@code requestId} is the server-generated {@code X-Request-Id} of the HTTP request; it is REQUIRED for request-borne
 *       actors ({@link ActorType#HUMAN_ADMIN}, {@link ActorType#SERVICE_ACCOUNT}) and absent for {@link ActorType#SYSTEM}
 *       work, which has no request. A {@code SYSTEM} id must start with {@code system:}.</li>
 * </ul>
 * Deliberately NO display name, email, permissions, IP address or user agent: mutable profile data is not audit identity.
 */
public record Actor(ActorType type, String id, String credentialId, String requestId) {

    static final int MAX_FIELD = 200;
    public static final String SYSTEM_PREFIX = "system:";

    public Actor {
        if (type == null) {
            throw new IllegalArgumentException("actor type required");
        }
        requireBounded(id, "actor id");
        if (credentialId != null) {
            requireBounded(credentialId, "actor credentialId");
        }
        if (requestId != null) {
            requireBounded(requestId, "actor requestId");
        }
        if (type == ActorType.SYSTEM) {
            if (!id.startsWith(SYSTEM_PREFIX) || id.length() == SYSTEM_PREFIX.length()) {
                throw new IllegalArgumentException("a SYSTEM actor id must be system:<name>");
            }
        } else if (requestId == null) {
            throw new IllegalArgumentException("a request-borne actor (" + type + ") requires its requestId");
        }
    }

    /** The application itself, e.g. {@code system("system:taint-worker")}. */
    public static Actor system(String id) {
        return new Actor(ActorType.SYSTEM, id, null, null);
    }

    private static void requireBounded(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_FIELD
                || value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException(name + " required: non-blank, no control chars, max " + MAX_FIELD);
        }
    }
}
