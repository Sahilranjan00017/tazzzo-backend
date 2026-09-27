package com.tazzzo.commerce.api;

import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;

/**
 * The ONE public location parser (PR-10B). Launch supports PIN only; lat/lng is reserved and
 * rejected. Rules (ratified): any {@code lat} or {@code lng} present → 400 INVALID_REQUEST (covers
 * lat-only, lng-only, and pin+lat/lng); no location → anonymous (permitted for list/PDP); a present
 * pin must satisfy {@link Pincode}. The serviceability endpoint requires a valid pin. No geocoding.
 * The PIN grammar is owned by {@link Pincode} — not re-specified here.
 */
final class CommerceLocationParser {

    private CommerceLocationParser() { }

    /** Category-products / PDP: PIN optional; anonymous permitted; any lat/lng → 400. */
    static LocationQuery parse(String pin, String lat, String lng) {
        rejectLatLng(lat, lng);
        if (isBlank(pin)) {
            return LocationQuery.anonymous();
        }
        return LocationQuery.ofPin(validPin(pin));
    }

    /** Serviceability: a valid PIN is REQUIRED; any lat/lng → 400; missing/blank pin → 400. */
    static Pincode requirePin(String pin, String lat, String lng) {
        rejectLatLng(lat, lng);
        if (isBlank(pin)) {
            throw new ConsumerFailures.InvalidRequest("pin is required");
        }
        return validPin(pin);
    }

    private static void rejectLatLng(String lat, String lng) {
        if (!isBlank(lat) || !isBlank(lng)) {
            throw new ConsumerFailures.InvalidRequest("lat/lng location is not supported");
        }
    }

    private static Pincode validPin(String pin) {
        String trimmed = pin.trim();
        if (!Pincode.isValid(trimmed)) {
            throw new ConsumerFailures.InvalidRequest("invalid pin");
        }
        return new Pincode(trimmed);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
