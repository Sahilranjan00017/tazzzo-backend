package com.tazzzo.location;

/** A validated WGS84 coordinate: both ordinates finite and within range. */
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("coordinate out of range");
        }
    }
}
