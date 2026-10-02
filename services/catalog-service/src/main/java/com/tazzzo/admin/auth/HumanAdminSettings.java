package com.tazzzo.admin.auth;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates {@link AdminAuthProperties} into the human admin trust policy and allowlist, or fails startup.
 *
 * <p><b>The all-or-nothing invariant.</b> No {@code tazzzo.admin.oidc.*} property set (blank counts as unset, so empty
 * environment placeholders are absent) and no users: human OIDC is DISABLED and the backend runs on the shared service
 * tokens exactly as before. Any OIDC property set: issuer, audience, hosted-domain and credential-label are ALL required.
 * Any user configured: OIDC is required. A partial or contradictory configuration is refused rather than half-enabled.
 *
 * <p>Error messages name properties and list positions, never a configured subject or email, so a failed startup does
 * not print the allowlist.
 */
public record HumanAdminSettings(Optional<GoogleOidcSettings> oidc, HumanAdminAllowlist allowlist) {

    /** Google's canonical issuer; the documented legacy form {@code accounts.google.com} is also accepted on tokens. */
    public static final String GOOGLE_ISSUER = "https://accounts.google.com";
    public static final URI GOOGLE_JWKS_URI = URI.create("https://www.googleapis.com/oauth2/v3/certs");
    public static final String PROVIDER_GOOGLE = "google";
    static final Set<String> KNOWN_ROLES = Set.of(AdminPrincipal.READER, AdminPrincipal.CMS_WRITER);

    private static final Pattern HOSTED_DOMAIN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final Pattern CREDENTIAL_LABEL = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final Pattern NO_WHITESPACE = Pattern.compile("^\\S+$");
    private static final int MAX_SUBJECT_LENGTH = 255;

    public boolean oidcEnabled() {
        return oidc.isPresent();
    }

    public static HumanAdminSettings from(AdminAuthProperties properties) {
        List<String> problems = new ArrayList<>();
        AdminAuthProperties.Oidc o = properties.getOidc();
        List<AdminAuthProperties.User> users = properties.getUsers() == null ? List.of() : properties.getUsers();

        boolean anyOidc = present(o.getIssuer()) || present(o.getAudience()) || present(o.getHostedDomain())
                || present(o.getCredentialLabel()) || present(o.getJwksUri());
        if (!anyOidc) {
            if (!users.isEmpty()) {
                fail(List.of("tazzzo.admin.users is configured but tazzzo.admin.oidc is not: human admins require OIDC"));
            }
            return new HumanAdminSettings(Optional.empty(), new HumanAdminAllowlist(Map.of()));
        }

        requirePresent(problems, "tazzzo.admin.oidc.issuer", o.getIssuer());
        requirePresent(problems, "tazzzo.admin.oidc.audience", o.getAudience());
        requirePresent(problems, "tazzzo.admin.oidc.hosted-domain", o.getHostedDomain());
        requirePresent(problems, "tazzzo.admin.oidc.credential-label", o.getCredentialLabel());
        if (!problems.isEmpty()) {
            fail(problems);
        }
        if (!GOOGLE_ISSUER.equals(o.getIssuer())) {
            problems.add("tazzzo.admin.oidc.issuer must be " + GOOGLE_ISSUER);
        }
        if (!NO_WHITESPACE.matcher(o.getAudience()).matches() || o.getAudience().length() > 256) {
            problems.add("tazzzo.admin.oidc.audience must be a single client id without whitespace");
        }
        if (!HOSTED_DOMAIN.matcher(o.getHostedDomain()).matches()) {
            problems.add("tazzzo.admin.oidc.hosted-domain must be a lower-case DNS domain");
        }
        if (!CREDENTIAL_LABEL.matcher(o.getCredentialLabel()).matches()) {
            problems.add("tazzzo.admin.oidc.credential-label must match " + CREDENTIAL_LABEL.pattern());
        }
        URI jwksUri = present(o.getJwksUri()) ? jwksUri(problems, o.getJwksUri()) : GOOGLE_JWKS_URI;

        Map<HumanAdminAllowlist.Key, HumanAdminAllowlist.Entry> entries = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            AdminAuthProperties.User u = users.get(i);
            String at = "tazzzo.admin.users[" + i + "]";
            if (u == null) {
                problems.add(at + " is empty");
                continue;
            }
            if (!PROVIDER_GOOGLE.equals(u.getProvider())) {
                problems.add(at + ".provider must be " + PROVIDER_GOOGLE);
            }
            String subject = u.getSubject();
            if (!present(subject) || !NO_WHITESPACE.matcher(subject).matches() || subject.length() > MAX_SUBJECT_LENGTH) {
                problems.add(at + ".subject must be a non-blank provider subject without whitespace");
            }
            String email = u.getEmail();
            if (!present(email) || !NO_WHITESPACE.matcher(email).matches() || email.indexOf('@') < 1) {
                problems.add(at + ".email must be a non-blank reference label of the form name@domain");
            }
            List<String> roles = u.getRoles() == null ? List.of() : u.getRoles();
            if (roles.isEmpty()) {
                problems.add(at + ".roles must name at least one role");
            }
            Set<String> roleSet = new LinkedHashSet<>();
            for (String role : roles) {
                if (!KNOWN_ROLES.contains(role)) {
                    problems.add(at + ".roles contains an unknown role (allowed: reader, cms-writer)");
                } else {
                    roleSet.add(role);
                }
            }
            if (present(subject) && u.getProvider() != null) {
                HumanAdminAllowlist.Key key = new HumanAdminAllowlist.Key(u.getProvider(), subject);
                if (entries.containsKey(key)) {
                    problems.add(at + " duplicates an earlier provider+subject");
                } else if (!roleSet.isEmpty()) {
                    entries.put(key, new HumanAdminAllowlist.Entry(roleSet, u.isEnabled()));
                }
            }
        }
        if (!problems.isEmpty()) {
            fail(problems);
        }
        GoogleOidcSettings settings = new GoogleOidcSettings(GOOGLE_ISSUER, o.getAudience(), o.getHostedDomain(),
                "oidc:" + PROVIDER_GOOGLE + ":" + o.getCredentialLabel(), jwksUri);
        return new HumanAdminSettings(Optional.of(settings), new HumanAdminAllowlist(entries));
    }

    /** HTTPS always; plain HTTP only for a loopback host (a local test key server), never a remote one. */
    private static URI jwksUri(List<String> problems, String raw) {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost();
            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            if (scheme.equals("https") || (scheme.equals("http") && loopback)) {
                return uri;
            }
        } catch (IllegalArgumentException e) {
            // reported below
        }
        problems.add("tazzzo.admin.oidc.jwks-uri must be an https URI");
        return GOOGLE_JWKS_URI;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void requirePresent(List<String> problems, String property, String value) {
        if (!present(value)) {
            problems.add(property + " is required when any tazzzo.admin.oidc property is set");
        }
    }

    private static void fail(List<String> problems) {
        throw new IllegalStateException("invalid human admin configuration: " + String.join("; ", problems));
    }
}
