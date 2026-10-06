package com.tazzzo.location;

/** The configured geo provider could not answer (timeout, outage, quota). Carries no provider text. */
public class GeoProviderUnavailableException extends RuntimeException {
    public GeoProviderUnavailableException() {
        super("geo provider unavailable");
    }
}
