package com.tazzzo.location;

import java.util.Optional;

/** The default: no geo provider configured, so a lat/lng request is refused exactly as before this port existed. */
public final class DisabledGeoPincodeResolver implements GeoPincodeResolver {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<String> resolve(GeoPoint point) {
        throw new IllegalStateException("no geo provider configured");
    }
}
