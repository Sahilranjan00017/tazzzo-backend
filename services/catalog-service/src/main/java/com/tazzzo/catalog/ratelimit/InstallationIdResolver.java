package com.tazzzo.catalog.ratelimit;

import java.util.Optional;

/**
 * Reads the optional {@code X-Tazzzo-Installation-Id} header (CAT-SEC-1 Q5-INSTALL-1).
 *
 * <p><b>It is an opaque RATE-LIMIT DIMENSION and nothing else.</b> Not authentication, not
 * authorization, not proof of device identity, not secret material. It never grants additional
 * catalogue capability, and it may be omitted, forged or rotated at will — which is exactly why the
 * IP bucket stays mandatory.
 *
 * <p>Absent or malformed simply means "this dimension does not apply". A malformed value is NOT an
 * error: rejecting the request would turn an anti-abuse hint into a de facto credential, and
 * accepting it as an identity would let arbitrary bytes become a bucket key.
 *
 * <p>The syntax is deliberately conservative — a bounded, printable, key-safe alphabet — and it
 * carries no security meaning whatsoever.
 */
public final class InstallationIdResolver {

    public static final String HEADER = "X-Tazzzo-Installation-Id";
    static final int MAX_LENGTH = 128;

    private InstallationIdResolver() {
    }

    public static Optional<String> resolve(String headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        String value = headerValue.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH || !isOpaqueToken(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static boolean isOpaqueToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ':';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
