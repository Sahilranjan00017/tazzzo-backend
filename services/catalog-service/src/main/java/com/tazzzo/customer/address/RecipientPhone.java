package com.tazzzo.customer.address;

import java.util.regex.Pattern;

/**
 * PR-12B — a normalized India delivery-contact phone number. This is DELIVERY CONTACT
 * information, NOT customer authentication identity: it may differ from the customer's login
 * phone, is never used to derive identity, issue an OTP, or log in, and is never logged or placed
 * in a metric tag.
 *
 * <p>Deliberately an address-owned value type mirroring {@code auth.otp.Phone}'s exact India E.164
 * shape/normalization rules, rather than importing that type directly — {@code customer.address}
 * must not conflate delivery-contact data with the auth domain's identity-owned phone type.
 */
final class RecipientPhone {

    private static final Pattern E164_INDIA = Pattern.compile("^\\+91[6-9][0-9]{9}$");
    private static final Pattern BARE_10_DIGIT = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern LEADING_ZERO = Pattern.compile("^0[6-9][0-9]{9}$");

    private RecipientPhone() {
    }

    /** @throws AddressFailure INVALID_REQUEST -- not a recognized India phone shape. */
    static String normalize(String raw) {
        if (raw == null) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 16) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        if (E164_INDIA.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (BARE_10_DIGIT.matcher(trimmed).matches()) {
            return "+91" + trimmed;
        }
        if (LEADING_ZERO.matcher(trimmed).matches()) {
            return "+91" + trimmed.substring(1);
        }
        throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
    }
}
