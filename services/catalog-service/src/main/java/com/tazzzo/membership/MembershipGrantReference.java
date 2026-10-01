package com.tazzzo.membership;

import java.util.regex.Pattern;

/**
 * PR-16A-1 — the idempotency identity of a grant: a structural {@link GrantSource} namespace plus an
 * opaque, case-sensitive, never-normalized reference. The unique index
 * {@code (grantSource, grantRef)} makes "one durable term per reference" a storage guarantee.
 */
public record MembershipGrantReference(GrantSource source, String reference) {

    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public MembershipGrantReference {
        if (source == null) {
            throw new IllegalArgumentException("grant source required");
        }
        if (reference == null || !REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("invalid grant reference shape");
        }
    }

    public static boolean isValidReference(String candidate) {
        return candidate != null && REFERENCE.matcher(candidate).matches();
    }
}
