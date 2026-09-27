package com.tazzzo.auth.otp;

import java.util.regex.Pattern;

/**
 * PR-11B — a strictly normalized India-only phone number. Launch jurisdiction is India, so this
 * type deliberately accepts nothing else: a client sending a non-Indian number gets a rejection, not
 * a silent guess at its country.
 *
 * <p><b>Accepted input:</b> E.164 ({@code +91} followed by exactly 10 digits whose first digit is
 * 6-9, the valid Indian mobile leading-digit range) OR the two common domestic shorthands
 * (10 bare digits, or a single leading {@code 0}) — each normalized through this ONE parser to the
 * SAME canonical form. No other shape is accepted; there is no guessing of a missing country code
 * beyond these two explicitly-normalized domestic forms.
 *
 * <p><b>Canonical stored/wire form:</b> {@code +91XXXXXXXXXX} — no spaces, no hyphens, always the
 * {@code +} prefix.
 *
 * <p>Phone is PII. This type intentionally does NOT override {@link #toString()} to something safe
 * — callers must go through {@link #masked()} for any log/error surface and must never pass
 * {@link #value()} to a logger, metric tag, or Redis/Mongo key without first passing it through a
 * keyed digest (see {@code OtpVerifierCodec}).
 */
public record Phone(String value) {

    private static final Pattern E164_INDIA = Pattern.compile("^\\+91[6-9][0-9]{9}$");
    private static final Pattern BARE_10_DIGIT = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern LEADING_ZERO = Pattern.compile("^0[6-9][0-9]{9}$");

    public Phone {
        if (value == null || !E164_INDIA.matcher(value).matches()) {
            throw new IllegalArgumentException("phone must be canonical +91 E.164");
        }
    }

    /**
     * @throws IllegalArgumentException the raw input is not a recognized India phone shape.
     *         Never guesses a country; never accepts letters, whitespace, or a malformed {@code +}
     *         prefix.
     */
    public static Phone parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("phone is required");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 16) {
            throw new IllegalArgumentException("phone has an invalid length");
        }
        if (E164_INDIA.matcher(trimmed).matches()) {
            return new Phone(trimmed);
        }
        if (BARE_10_DIGIT.matcher(trimmed).matches()) {
            return new Phone("+91" + trimmed);
        }
        if (LEADING_ZERO.matcher(trimmed).matches()) {
            return new Phone("+91" + trimmed.substring(1));
        }
        throw new IllegalArgumentException("phone is not a supported India number");
    }

    /** Safe for logs/errors: never the full number. */
    public String masked() {
        return "+91******" + value.substring(value.length() - 4);
    }
}
