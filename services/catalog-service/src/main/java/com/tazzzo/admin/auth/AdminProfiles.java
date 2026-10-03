package com.tazzzo.admin.auth;

import com.tazzzo.common.audit.ActorType;

import java.util.Optional;

/**
 * Display metadata for an authenticated {@link AdminPrincipal}, for the CMS bootstrap ({@code GET /api/v1/admin/me}).
 * Today that is only the allowlist's configured email LABEL of a HUMAN_ADMIN. It is never identity, never an
 * authorization input and never audited: the principal (and so {@code Actor.id}) stays {@code google:<sub>} whatever the
 * label says, and a token's own email is never consulted. Service accounts have no label.
 */
public final class AdminProfiles {

    private static final String GOOGLE_ACTOR_PREFIX = HumanAdminSettings.PROVIDER_GOOGLE + ":";

    private final HumanAdminAllowlist allowlist;

    public AdminProfiles(HumanAdminAllowlist allowlist) {
        this.allowlist = allowlist;
    }

    /** The configured email label of a Google HUMAN_ADMIN ({@code google:<sub>}); empty for every other principal. */
    public Optional<String> emailLabel(AdminPrincipal principal) {
        if (principal.actorType() != ActorType.HUMAN_ADMIN || !principal.actorId().startsWith(GOOGLE_ACTOR_PREFIX)) {
            return Optional.empty();
        }
        String subject = principal.actorId().substring(GOOGLE_ACTOR_PREFIX.length());
        return allowlist.emailLabel(HumanAdminSettings.PROVIDER_GOOGLE, subject);
    }
}
