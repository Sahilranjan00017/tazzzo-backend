package com.tazzzo.serviceability;

import com.tazzzo.commerce.contract.Pincode;

/**
 * Internal resolution boundary (PR-06, STEP 11). Takes a VALIDATED {@link Pincode} (the frozen
 * ^[1-9][0-9]{5}$ contract), returns a domain {@link ServiceabilityResolution} — never a public
 * DTO, never a Mongo document. Commerce Read (PR-08) composes this with Inventory; this module
 * itself never calls Inventory/Pricing/Media. Location precedence (selected address, future
 * lat/lng) resolves to a PIN/area UPSTREAM of this port — the port stays source-agnostic.
 */
public interface ServiceabilityReadPort {

    ServiceabilityResolution resolveByPincode(Pincode pin);
}
