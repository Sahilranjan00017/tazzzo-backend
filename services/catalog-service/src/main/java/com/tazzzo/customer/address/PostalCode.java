package com.tazzzo.customer.address;

import java.util.regex.Pattern;

/**
 * PR-12B — the frozen India PIN shape ({@code ^[1-9][0-9]{5}$}), the SAME rule
 * {@code commerce.contract.Pincode} enforces. A small address-owned copy rather than importing
 * {@code Pincode} directly for the value-object itself (so {@code customer.address}'s own field
 * validation does not couple to the commerce contract type) — the two are converted at the ONE
 * seam that calls into serviceability ({@link AddressServiceabilityEvaluator}).
 */
final class PostalCode {

    private static final Pattern SIX_DIGITS = Pattern.compile("^[1-9][0-9]{5}$");

    private PostalCode() {
    }

    /** @throws AddressFailure INVALID_REQUEST -- not a 6-digit Indian PIN. */
    static String normalize(String raw) {
        if (raw == null) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        String trimmed = raw.strip();
        if (!SIX_DIGITS.matcher(trimmed).matches()) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        return trimmed;
    }
}
