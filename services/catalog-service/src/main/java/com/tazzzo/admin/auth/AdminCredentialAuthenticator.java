package com.tazzzo.admin.auth;

/** One credential family of the INTERNAL admin surface (shared service tokens, Google OIDC ID tokens). */
public interface AdminCredentialAuthenticator {

    AdminAuthentication authenticate(AdminBearerCredential credential);
}
