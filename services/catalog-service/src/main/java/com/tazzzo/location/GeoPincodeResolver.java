package com.tazzzo.location;

import java.util.Optional;

/**
 * The seam to an external reverse-geocoding provider: a coordinate in, an Indian 6-digit PIN out. The platform never
 * stores coordinates and never trusts a client-supplied PIN-for-coordinate; serviceability is always decided from the
 * PIN this port returns, by the existing serviceability domain.
 *
 * <p>Returns the PIN as a string (the commerce contract validates the grammar), so this module depends on no other. A
 * point the provider cannot place in a PIN is {@link Optional#empty()}; an outage is
 * {@link GeoProviderUnavailableException}. No concrete external provider ships in this repository: selecting one needs a
 * business decision and a credential (see docs/ops/GEO_PROVIDER.md), so the default is {@link DisabledGeoPincodeResolver}.
 */
public interface GeoPincodeResolver {

    /** False when no provider is configured; callers then keep the PIN-only contract. */
    boolean enabled();

    Optional<String> resolve(GeoPoint point);
}
