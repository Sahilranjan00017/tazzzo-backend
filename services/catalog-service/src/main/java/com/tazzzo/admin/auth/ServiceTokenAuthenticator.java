package com.tazzzo.admin.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The shared SERVICE_ACCOUNT bearer tokens ({@code tazzzo.auth.cms-token}, {@code tazzzo.auth.read-token}), unchanged in
 * meaning: NO default credentials (an unset token disables that role, fail-closed), and when both are (mis)configured to
 * the SAME value the cms-writer wins, because it is checked first. Comparison is {@link MessageDigest#isEqual}, which does
 * not short-circuit on the first differing byte (the length of a configured token is not treated as secret).
 *
 * <p>A credential that matches neither token is {@link AdminAuthentication.NotApplicable}: it may be another family's.
 */
@Component
public class ServiceTokenAuthenticator implements AdminCredentialAuthenticator {

    private final byte[] cmsToken;
    private final byte[] readToken;

    public ServiceTokenAuthenticator(@Value("${tazzzo.auth.cms-token:}") String cmsToken,
                                     @Value("${tazzzo.auth.read-token:}") String readToken) {
        this.cmsToken = configured(cmsToken);
        this.readToken = configured(readToken);
    }

    private static byte[] configured(String token) {
        return token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public AdminAuthentication authenticate(AdminBearerCredential credential) {
        byte[] presented = credential.value().getBytes(StandardCharsets.UTF_8);
        if (cmsToken != null && MessageDigest.isEqual(presented, cmsToken)) {
            return new AdminAuthentication.Authenticated(AdminPrincipal.sharedToken(AdminPrincipal.CMS_WRITER));
        }
        if (readToken != null && MessageDigest.isEqual(presented, readToken)) {
            return new AdminAuthentication.Authenticated(AdminPrincipal.sharedToken(AdminPrincipal.READER));
        }
        return AdminAuthentication.NOT_APPLICABLE;
    }
}
