package com.tazzzo.admin.auth;

/**
 * The closed set of reasons an authenticator can refuse a credential it recognised, each with its fixed HTTP status.
 *
 * <p><b>401 vs 403 contract.</b> A credential that does not satisfy the configured admin IDENTITY trust policy is
 * unauthenticated (401): a bad or unverifiable token ({@link #INVALID_TOKEN}), an expired one ({@link #EXPIRED_TOKEN}),
 * a Google identity outside the configured Workspace hosted domain, including a personal account with no {@code hd}
 * ({@link #DOMAIN_MISMATCH}), and an unverified email ({@link #EMAIL_UNVERIFIED}). Identity proven but admin access not
 * granted by the Tazzzo allowlist is forbidden (403): {@link #NOT_ALLOWLISTED}, {@link #DISABLED}.
 */
public enum AdminAuthRejection {
    INVALID_TOKEN(401),
    EXPIRED_TOKEN(401),
    DOMAIN_MISMATCH(401),
    EMAIL_UNVERIFIED(401),
    NOT_ALLOWLISTED(403),
    DISABLED(403);

    private final int httpStatus;

    AdminAuthRejection(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
