package com.tazzzo.admin.auth;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stage A of INTERNAL admin access: which principal, if any, does this credential prove? The order is fixed and
 * deliberate: the exact shared service-token match first (a match never reaches OIDC), then Google OIDC. The first
 * authenticator that recognises the credential decides; when none does the result is
 * {@link AdminAuthentication.NotApplicable} and the caller answers 401. Operation authorization (may this principal
 * write?) is the caller's separate Stage B.
 */
@Component
public class AdminAuthenticatorChain {

    private final List<AdminCredentialAuthenticator> authenticators;

    public AdminAuthenticatorChain(ServiceTokenAuthenticator serviceTokens, GoogleOidcAuthenticator googleOidc) {
        this.authenticators = List.of(serviceTokens, googleOidc);
    }

    public AdminAuthentication authenticate(AdminBearerCredential credential) {
        for (AdminCredentialAuthenticator authenticator : authenticators) {
            AdminAuthentication result = authenticator.authenticate(credential);
            if (!(result instanceof AdminAuthentication.NotApplicable)) {
                return result;
            }
        }
        return AdminAuthentication.NOT_APPLICABLE;
    }
}
