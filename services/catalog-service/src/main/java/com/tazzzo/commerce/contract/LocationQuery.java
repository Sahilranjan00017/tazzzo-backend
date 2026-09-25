package com.tazzzo.commerce.contract;

import java.util.Optional;

/**
 * The customer location a browse request is scoped to (Phase 3.1 precedence). Phase 1 supports
 * a {@link Pincode}; latitude/longitude are reserved for future resolution and are represented
 * but not acted upon here. {@link #anonymous()} models no-location browse (identity/price/media
 * render; stock is UNKNOWN and items are not buyable).
 *
 * <p>Deliberately absent: any {@code fulfillmentLocationId}. That value is internal and
 * server-derived; it never appears in a client-supplied query or a public DTO.
 */
public record LocationQuery(Pincode pin, Double lat, Double lng) {

    /** No location supplied — anonymous browse. */
    public static LocationQuery anonymous() {
        return new LocationQuery(null, null, null);
    }

    public static LocationQuery ofPin(Pincode pin) {
        return new LocationQuery(pin, null, null);
    }

    /** Reserved for future lat/lng resolution; carried but not yet resolved to an area. */
    public static LocationQuery ofLatLng(double lat, double lng) {
        return new LocationQuery(null, lat, lng);
    }

    public boolean isPresent() {
        return pin != null || (lat != null && lng != null);
    }

    public Optional<Pincode> pincode() {
        return Optional.ofNullable(pin);
    }
}
