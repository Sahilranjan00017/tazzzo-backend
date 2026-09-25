package com.tazzzo.commerce.contract;

import java.util.regex.Pattern;

/**
 * A validated 6-digit Indian PIN code — the Phase-1 location lookup mechanism (Phase 3.1).
 * Immutable value type; construction validates format. A leading digit of 0 is not a valid
 * Indian PIN, so the first digit is 1-9. This is a request/contract primitive only; it does NOT
 * resolve serviceability or any fulfillment location (that is a future server-side concern).
 */
public record Pincode(String value) {

    private static final Pattern SIX_DIGITS = Pattern.compile("^[1-9][0-9]{5}$");

    public Pincode {
        if (value == null || !SIX_DIGITS.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid Indian PIN (must be 6 digits, first 1-9): " + value);
        }
    }

    public static boolean isValid(String candidate) {
        return candidate != null && SIX_DIGITS.matcher(candidate).matches();
    }
}
