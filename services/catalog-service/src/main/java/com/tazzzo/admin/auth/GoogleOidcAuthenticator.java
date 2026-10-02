package com.tazzzo.admin.auth;

import com.tazzzo.common.audit.ActorType;

/**
 * Google Workspace human admins: Stage A ({@link GoogleOidcVerifier}: is this a genuine, in-domain, verified Google
 * identity?) then Stage B ({@link HumanAdminAllowlist}: is that subject a Tazzzo admin, with which roles?). The principal
 * is {@code HUMAN_ADMIN}, {@code google:<sub>} (never the email), with the allowlist's roles (never token claims) and the
 * non-secret credential label. When OIDC is not configured this authenticator recognises nothing.
 */
public final class GoogleOidcAuthenticator implements AdminCredentialAuthenticator {

    private static final GoogleOidcAuthenticator DISABLED = new GoogleOidcAuthenticator();

    private final GoogleOidcVerifier verifier;
    private final HumanAdminAllowlist allowlist;
    private final String credentialId;

    public GoogleOidcAuthenticator(GoogleOidcVerifier verifier, HumanAdminAllowlist allowlist, String credentialId) {
        if (verifier == null || allowlist == null || credentialId == null || credentialId.isBlank()) {
            throw new IllegalArgumentException("an enabled OIDC authenticator needs a verifier, allowlist and credential id");
        }
        this.verifier = verifier;
        this.allowlist = allowlist;
        this.credentialId = credentialId;
    }

    private GoogleOidcAuthenticator() {
        this.verifier = null;
        this.allowlist = null;
        this.credentialId = null;
    }

    public static GoogleOidcAuthenticator disabled() {
        return DISABLED;
    }

    public boolean enabled() {
        return verifier != null;
    }

    @Override
    public AdminAuthentication authenticate(AdminBearerCredential credential) {
        if (!enabled()) {
            return AdminAuthentication.NOT_APPLICABLE;
        }
        GoogleOidcVerifier.VerifiedIdentity identity;
        try {
            identity = verifier.verify(credential.value());
        } catch (GoogleOidcVerifier.Rejected e) {
            return new AdminAuthentication.Rejected(e.reason());
        }
        return switch (allowlist.resolve(HumanAdminSettings.PROVIDER_GOOGLE, identity.subject())) {
            case HumanAdminAllowlist.Resolution.Refused refused -> new AdminAuthentication.Rejected(refused.reason());
            case HumanAdminAllowlist.Resolution.Granted granted -> new AdminAuthentication.Authenticated(
                    new AdminPrincipal(ActorType.HUMAN_ADMIN, HumanAdminSettings.PROVIDER_GOOGLE + ":" + identity.subject(),
                            credentialId, granted.roles()));
        };
    }
}
