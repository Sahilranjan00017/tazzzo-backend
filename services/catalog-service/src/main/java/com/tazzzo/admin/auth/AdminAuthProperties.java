package com.tazzzo.admin.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Human admin authentication configuration (raw binding; {@link HumanAdminSettings#from} validates it).
 *
 * <p>{@code tazzzo.admin.oidc.*} — the Google OIDC trust policy: {@code issuer} (must be {@code https://accounts.google.com}),
 * {@code audience} (the admin OAuth client id; non-secret), {@code hosted-domain} (the company Workspace domain, matched
 * exactly against the verified {@code hd} claim), {@code credential-label} (short non-secret label recorded in audit as
 * {@code oidc:google:<label>}) and optional {@code jwks-uri} (defaults to Google's published key set).
 *
 * <p>{@code tazzzo.admin.users[n].*} — the allowlist: {@code provider} ({@code google}), {@code subject} (the Google
 * {@code sub}, the identity), {@code email} (reference label only, never an authentication input), {@code roles}
 * ({@code reader} and/or {@code cms-writer}) and {@code enabled} (default {@code true}).
 *
 * <p>ALL-OR-NOTHING: with no OIDC property set, human OIDC is DISABLED and only the shared service tokens authenticate;
 * any OIDC property set requires all of issuer, audience, hosted-domain and credential-label; users require OIDC.
 * Anything else fails startup. None of these values is secret; the Google OAuth client secret is NOT needed here.
 */
@ConfigurationProperties(prefix = "tazzzo.admin")
public class AdminAuthProperties {

    private final Oidc oidc = new Oidc();
    private List<User> users = new ArrayList<>();

    public Oidc getOidc() {
        return oidc;
    }

    public List<User> getUsers() {
        return users;
    }

    public void setUsers(List<User> users) {
        this.users = users;
    }

    public static class Oidc {

        private String issuer;
        private String audience;
        private String hostedDomain;
        private String credentialLabel;
        private String jwksUri;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getHostedDomain() {
            return hostedDomain;
        }

        public void setHostedDomain(String hostedDomain) {
            this.hostedDomain = hostedDomain;
        }

        public String getCredentialLabel() {
            return credentialLabel;
        }

        public void setCredentialLabel(String credentialLabel) {
            this.credentialLabel = credentialLabel;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }
    }

    public static class User {

        private String provider;
        private String subject;
        private String email;
        private List<String> roles = new ArrayList<>();
        private boolean enabled = true;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
