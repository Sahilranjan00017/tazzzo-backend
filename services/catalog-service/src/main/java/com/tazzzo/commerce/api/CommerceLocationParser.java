package com.tazzzo.commerce.api;

import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.location.GeoPincodeResolver;
import com.tazzzo.location.GeoPoint;
import com.tazzzo.location.GeoProviderUnavailableException;

import java.util.Optional;

/**
 * The ONE public location parser (PR-10B). Launch supports PIN only; lat/lng is reserved and
 * rejected. Rules (ratified): any {@code lat} or {@code lng} present → 400 INVALID_REQUEST (covers
 * lat-only, lng-only, and pin+lat/lng); no location → anonymous (permitted for list/PDP); a present
 * pin must satisfy {@link Pincode}. The serviceability endpoint requires a valid pin. No geocoding.
 * The PIN grammar is owned by {@link Pincode} — not re-specified here.
 *
 * <p>When a {@link GeoPincodeResolver} is ENABLED (an operator configured a provider), {@code lat}+{@code lng} together
 * (and no {@code pin}) are resolved to a PIN by it on the SERVICEABILITY endpoint only; category-products and PDP stay
 * PIN-only (a provider call there would precede admission charging). With the default disabled resolver nothing changes.
 * Coordinates are never stored or logged. A point with no PIN is outside coverage (anonymous / not serviceable); a
 * provider outage is 503, never a guess.
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
        return requirePin(pin, lat, lng, null);
    }

    /** As above; with an enabled resolver, lat+lng may stand in for the PIN. Empty = a point outside any PIN. */
    static Pincode requirePin(String pin, String lat, String lng, GeoPincodeResolver geo) {
        if (usesGeo(geo, lat, lng)) {
            if (!isBlank(pin)) {
                throw new ConsumerFailures.InvalidRequest("pin and lat/lng are mutually exclusive");
            }
            return geoPin(geo, lat, lng).orElse(null);
        }
        rejectLatLng(lat, lng);
        if (isBlank(pin)) {
            throw new ConsumerFailures.InvalidRequest("pin is required");
        }
        return validPin(pin);
    }

    static boolean usesGeo(GeoPincodeResolver geo, String lat, String lng) {
        return geo != null && geo.enabled() && (!isBlank(lat) || !isBlank(lng));
    }

    /** Both ordinates required and in range; the provider's answer must itself be a valid PIN. */
    private static Optional<Pincode> geoPin(GeoPincodeResolver geo, String lat, String lng) {
        if (isBlank(lat) || isBlank(lng)) {
            throw new ConsumerFailures.InvalidRequest("lat and lng must be supplied together");
        }
        GeoPoint point;
        try {
            point = new GeoPoint(Double.parseDouble(lat.trim()), Double.parseDouble(lng.trim()));
        } catch (IllegalArgumentException e) {
            throw new ConsumerFailures.InvalidRequest("invalid lat/lng");
        }
        try {
            return geo.resolve(point).map(String::trim).filter(Pincode::isValid).map(Pincode::new);
        } catch (GeoProviderUnavailableException e) {
            throw new ConsumerFailures.Unavailable("geo provider unavailable");
        }
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
