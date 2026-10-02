package com.tazzzo.admin.auth;

import java.util.Optional;

/**
 * The bearer credential presented to the INTERNAL admin surface, parsed from the {@code Authorization} header. The raw
 * value is readable only inside {@code com.tazzzo.admin.auth} (the authenticators); it is never logged, stored or
 * audited, and {@link #toString()} is redacted so it cannot leak through string concatenation or a debugger dump.
 *
 * <p>Nothing here classifies the credential (opaque service token vs JWT): every authenticator in the chain judges it
 * for itself. Routing by string shape ({@code contains(".")}, segment count) is deliberately absent.
 */
public final class AdminBearerCredential {

    private static final String BEARER_PREFIX = "Bearer ";

    private final String value;

    private AdminBearerCredential(String value) {
        this.value = value;
    }

    /** The credential of an {@code Authorization: Bearer <value>} header; empty when absent, not Bearer, or blank. */
    public static Optional<AdminBearerCredential> fromAuthorizationHeader(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String value = header.substring(BEARER_PREFIX.length());
        return value.isBlank() ? Optional.empty() : Optional.of(new AdminBearerCredential(value));
    }

    String value() {
        return value;
    }

    @Override
    public String toString() {
        return "AdminBearerCredential[redacted]";
    }
}
