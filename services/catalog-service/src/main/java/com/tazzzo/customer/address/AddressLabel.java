package com.tazzzo.customer.address;

import java.util.Locale;

/**
 * PR-12B — the bounded, closed label vocabulary for a saved address. Deliberately NOT an arbitrary
 * high-cardinality string: custom label text is explicitly deferred (mission §7) unless a future
 * PR clearly needs it.
 */
public enum AddressLabel {
    HOME, WORK, OTHER;

    static AddressLabel parse(String raw) {
        if (raw == null) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        try {
            return AddressLabel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
    }
}
