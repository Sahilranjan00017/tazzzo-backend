package com.tazzzo.auth;

import java.util.regex.Pattern;

/**
 * PR-11A — an opaque, stable customer identity. NOT a phone number, NOT sequential, NOT derived
 * from any PII. The value carries no semantics beyond "this identifies one customer" — future
 * PRs decide how it is minted (a generated id, never a database auto-increment).
 *
 * <p>Bounded length and a conservative alphabet only: this is a security-boundary value object,
 * not a display string.
 */
public record CustomerId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^CUS_[A-Za-z0-9_-]{6,64}$");

    public CustomerId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid customer id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }
}
