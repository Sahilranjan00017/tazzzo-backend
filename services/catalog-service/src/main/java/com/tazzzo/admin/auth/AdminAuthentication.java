package com.tazzzo.admin.auth;

/**
 * The outcome of presenting one credential to an {@link AdminCredentialAuthenticator}: not this authenticator's
 * credential, an authenticated {@link AdminPrincipal}, or a definite refusal with a bounded reason.
 */
public sealed interface AdminAuthentication {

    /** This authenticator does not recognise the credential; the next one may. */
    record NotApplicable() implements AdminAuthentication {
    }

    record Authenticated(AdminPrincipal principal) implements AdminAuthentication {
        public Authenticated {
            if (principal == null) {
                throw new IllegalArgumentException("principal required");
            }
        }
    }

    /** The credential was judged and refused. No principal exists. */
    record Rejected(AdminAuthRejection reason) implements AdminAuthentication {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason required");
            }
        }
    }

    AdminAuthentication NOT_APPLICABLE = new NotApplicable();
}
