package com.tazzzo.customer.profile;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * PR-12A — the ONE place email is normalized/validated. Deliberately NOT a full RFC 5322
 * implementation — a practical, bounded shape check only. Email is optional, UNVERIFIED profile
 * data in this PR: it is never an authentication identity, never used to log in, and this class
 * makes no uniqueness claim.
 *
 * <p>Canonicalization policy (chosen once, applied consistently): the WHOLE address is lower-cased,
 * not just the domain — simpler than a mixed-case local part policy, and avoids ambiguity about
 * which comparison rule applies later.
 */
final class Emails {

    static final int MAX_LENGTH = 254;

    /** Practical shape only: local-part charset + at-least-one-dot domain. Not full RFC 5322. */
    private static final Pattern SHAPE =
            Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");

    private Emails() {
    }

    /** @return the normalized value, or {@code null} if the trimmed input was empty */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw invalid();
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw invalid();
            }
        }
        String canonical = trimmed.toLowerCase(Locale.ROOT);
        if (!SHAPE.matcher(canonical).matches()) {
            throw invalid();
        }
        return canonical;
    }

    private static CustomerProfileFailure invalid() {
        return new CustomerProfileFailure(CustomerProfileFailure.Reason.INVALID_REQUEST);
    }
}
