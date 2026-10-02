package com.tazzzo.admin.auth;

import java.net.URI;

/**
 * The VALIDATED Google OIDC trust policy (see {@link HumanAdminSettings#from}). {@code credentialId} is the non-secret
 * audit label {@code oidc:google:<credential-label>}: it names the credential family, never a token.
 */
public record GoogleOidcSettings(String issuer, String audience, String hostedDomain, String credentialId, URI jwksUri) {
}
