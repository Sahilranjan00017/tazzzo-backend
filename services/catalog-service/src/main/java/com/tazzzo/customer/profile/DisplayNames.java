package com.tazzzo.customer.profile;

/**
 * PR-12A — the ONE place displayName is normalized/validated. Deliberately Unicode-friendly: a
 * legitimate name is any human-readable text, not an ASCII-letters-only string — {@code "José"} and
 * {@code "李明"} must remain valid. The only rejections are structural (control characters, length),
 * never script/alphabet.
 */
final class DisplayNames {

    static final int MAX_CODE_POINTS = 80;

    private DisplayNames() {
    }

    /** @return the normalized value, or {@code null} if the trimmed input was empty (never stored
     *          as an empty string) */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_CODE_POINTS) {
            throw new CustomerProfileFailure(CustomerProfileFailure.Reason.INVALID_REQUEST);
        }
        trimmed.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                throw new CustomerProfileFailure(CustomerProfileFailure.Reason.INVALID_REQUEST);
            }
        });
        return trimmed;
    }
}
