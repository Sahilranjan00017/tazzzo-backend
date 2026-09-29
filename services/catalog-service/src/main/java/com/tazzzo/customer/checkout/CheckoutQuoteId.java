package com.tazzzo.customer.checkout;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-13A — an opaque, CSPRNG-generated checkout quote identity ({@code CHKQ_...}). Mirrors
 * {@code AddressId}/{@code CustomerId}: never sequential, never derived from any input, bounded
 * length, conservative alphabet.
 */
public record CheckoutQuoteId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^CHKQ_[A-Za-z0-9_-]{6,64}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public CheckoutQuoteId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid checkout quote id shape");
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && PATTERN.matcher(candidate).matches();
    }

    /** Generated ONCE per request, BEFORE the transaction, so every callback retry reuses the same id. */
    public static CheckoutQuoteId generate() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new CheckoutQuoteId("CHKQ_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
