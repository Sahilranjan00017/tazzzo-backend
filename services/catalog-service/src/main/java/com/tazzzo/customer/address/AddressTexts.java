package com.tazzzo.customer.address;

/**
 * PR-12B — the ONE place every human-readable address field is normalized/validated. Deliberately
 * Unicode-friendly (no ASCII-only restriction) and address-owned: a small, self-contained
 * validator rather than importing {@code customer.profile.DisplayNames} (which would create a
 * {@code customer.address -> customer.profile} dependency the mission explicitly forbids).
 */
final class AddressTexts {

    private AddressTexts() {
    }

    /** Required field: non-null, non-blank after trim, bounded length, no control characters. */
    static String required(String raw, int maxCodePoints) {
        if (raw == null) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        return validated(trimmed, maxCodePoints);
    }

    /** Optional field: trims, empty-after-trim normalizes to {@code null} (never persisted as an
     *  empty string), otherwise bounded/validated the same as a required field. */
    static String optional(String raw, int maxCodePoints) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return validated(trimmed, maxCodePoints);
    }

    private static String validated(String trimmed, int maxCodePoints) {
        if (trimmed.codePointCount(0, trimmed.length()) > maxCodePoints) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        trimmed.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
            }
        });
        return trimmed;
    }
}
