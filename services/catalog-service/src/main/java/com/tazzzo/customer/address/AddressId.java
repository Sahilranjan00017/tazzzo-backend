package com.tazzzo.customer.address;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-12B — an opaque, CSPRNG-generated address identity. Mirrors {@code auth.CustomerId}/
 * {@code auth.SessionId} exactly: never sequential, never derived from any PII, bounded length,
 * conservative alphabet, never a raw Mongo ObjectId.
 */
public record AddressId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^ADDR_[A-Za-z0-9_-]{6,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public AddressId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid address id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    /** A fresh, cryptographically random opaque address id. Generated ONCE per create attempt,
     *  BEFORE entering any transaction, so a transaction retry reuses the SAME id rather than
     *  minting a new one on every attempt. */
    public static AddressId generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new AddressId("ADDR_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
