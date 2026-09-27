package com.tazzzo.auth;

import java.util.regex.Pattern;

/**
 * PR-11A — an opaque session identity bound into a customer access token. Carries no PII and no
 * semantics beyond "this is one login session". Server-side session persistence/revocation is a
 * PR-11C concern (session lifecycle); PR-11A's token verification is cryptographic/time validity
 * only — see {@link CustomerAccessTokenCodec}.
 */
public record SessionId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^SES_[A-Za-z0-9_-]{6,64}$");

    public SessionId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid session id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }
}
