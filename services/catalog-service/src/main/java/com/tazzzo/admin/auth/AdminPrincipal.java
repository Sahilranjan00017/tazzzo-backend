package com.tazzzo.admin.auth;

import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.ActorType;

import java.util.Set;

/**
 * The authenticated caller of the INTERNAL admin surface ({@code /api/**}), produced only by the admin authenticators
 * (attached by {@code ApiAuthFilter}) and read by controllers through {@link AdminPrincipalResolver}. It converts INTO a
 * neutral audit {@link Actor} (never the reverse: {@code common.audit} knows nothing about admin authentication). It is
 * provider-neutral: downstream code never learns how the caller authenticated.
 *
 * <p>The shared admin tokens are mapped to explicit {@link ActorType#SERVICE_ACCOUNT} principals
 * ({@code service:cms-writer}, {@code service:reader}): a shared token says WHICH CREDENTIAL acted, never WHICH PERSON,
 * even when a person uses it. {@link ActorType#HUMAN_ADMIN} principals come from per-person authentication (Google OIDC:
 * {@code google:<sub>}, roles from the backend allowlist). {@code credentialId} is a stable, non-secret label of the
 * credential; token material and email are never stored here.
 *
 * <p>Roles keep the existing coarse semantics: {@value #READER} may read (GET only), {@value #CMS_WRITER} may read and write.
 * {@value #AUDIT_READER} is NARROW: it grants only the admin audit-read API (and {@code /me}), only to a
 * {@link ActorType#HUMAN_ADMIN}, and confers no catalogue read and no write. Shared service tokens never carry it.
 *
 * <p>{@value #ORDER_OPS} and {@value #SUPPORT_AGENT} are the STAFF roles: each is valid only for a {@link ActorType#HUMAN_ADMIN}
 * and grants access ONLY to its own namespace under {@code /api/v1/admin} (see {@link AdminAccessPolicy}); neither confers any
 * catalogue read or write, and neither the shared tokens nor the catalogue roles reach the staff namespaces (customer personal data).
 */
public record AdminPrincipal(ActorType actorType, String actorId, String credentialId, Set<String> roles) {

    public static final String READER = "reader";
    public static final String CMS_WRITER = "cms-writer";
    public static final String AUDIT_READER = "audit-reader";
    /** STAFF role: operate customer orders (read + transition). Human admins only; sees customer personal data. */
    public static final String ORDER_OPS = "order-ops";
    /** STAFF role: handle customer support cases; may read orders (never change them). Human admins only. */
    public static final String SUPPORT_AGENT = "support-agent";

    public AdminPrincipal {
        if (actorType == null || actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("admin principal requires an actor type and id");
        }
        if (credentialId != null && credentialId.isBlank()) {
            throw new IllegalArgumentException("credentialId must be non-blank when present");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("admin principal requires at least one role");
        }
        roles = Set.copyOf(roles);
    }

    /** The principal of a SHARED service token: a service account named after its role. */
    public static AdminPrincipal sharedToken(String role) {
        return new AdminPrincipal(ActorType.SERVICE_ACCOUNT, "service:" + role, "shared-token:" + role, Set.of(role));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** May this principal perform a state-changing (non-GET) request? */
    public boolean canWrite() {
        return hasRole(CMS_WRITER);
    }

    /** May this principal read the general INTERNAL GET surface? {@value #AUDIT_READER} alone does not grant it. */
    public boolean canReadCatalog() {
        return hasRole(READER) || hasRole(CMS_WRITER);
    }

    /** Staff roles are per-person by construction: a shared service token never carries one. */
    public boolean isStaff() {
        return actorType == ActorType.HUMAN_ADMIN && (hasRole(ORDER_OPS) || hasRole(SUPPORT_AGENT));
    }

    /** May this principal read the admin audit ledger? Only a per-person human admin holding {@value #AUDIT_READER}. */
    public boolean canReadAudit() {
        return actorType == ActorType.HUMAN_ADMIN && hasRole(AUDIT_READER);
    }

    /** The audit actor of one request: this principal plus the server-generated request id. */
    public Actor toActor(String requestId) {
        return new Actor(actorType, actorId, credentialId, requestId);
    }
}
