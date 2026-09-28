package com.tazzzo.customer.address;

/**
 * PR-12B — latitude/longitude are treated as ONE pair, never independently. Either both are
 * absent, or both are supplied and both are valid finite coordinates — a partially-supplied or
 * malformed pair is always rejected, never silently dropped or half-applied.
 */
final class Coordinates {

    private Coordinates() {
    }

    record Pair(Double latitude, Double longitude) {
    }

    /** @throws AddressFailure INVALID_REQUEST -- exactly one of the pair supplied, out of range,
     *          or non-finite (NaN/Infinity). */
    static Pair validate(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return new Pair(null, null);
        }
        if (latitude == null || longitude == null) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new AddressFailure(AddressFailure.Reason.INVALID_REQUEST);
        }
        return new Pair(latitude, longitude);
    }
}
