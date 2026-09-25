package com.tazzzo.serviceability;

import java.util.List;

/**
 * Internal admin/config command: atomically create or CAS-replace the COMPLETE routing config
 * for one pincode (STEP 16 — whole-configuration replacement, so routing is never observable
 * half-updated). {@code expectedVersion} null = create (v1); value = CAS update to v+1.
 * No HTTP/CMS endpoint exposes this.
 */
public record UpsertServiceAreaCommand(
        String pincode,
        String serviceAreaId,
        List<ServiceabilityRoute> routes,
        String source,
        Long expectedVersion
) { }
