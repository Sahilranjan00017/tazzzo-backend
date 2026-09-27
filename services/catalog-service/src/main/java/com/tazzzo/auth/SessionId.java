package com.tazzzo.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-11A — an opaque session identity bound into a customer access token. Carries no PII and no
 * semantics beyond "this is one login session". PR-11C adds the actual server-side session
 * persistence/revocation lifecycle (see {@code com.tazzzo.auth.session}); PR-11A's token
 * verification alone is cryptographic/time validity only — see {@link CustomerAccessTokenCodec}.
 *
 * <p>PR-11C: {@link #generate()} is the actual minting seam for a real login session id.
 */
public record SessionId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^SES_[A-Za-z0-9_-]{6,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public SessionId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid session id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    /** A fresh, cryptographically random opaque session id — never sequential, no PII. */
    public static SessionId generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new SessionId("SES_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
