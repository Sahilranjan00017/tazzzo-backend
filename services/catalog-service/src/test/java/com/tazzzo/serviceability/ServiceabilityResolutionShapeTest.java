package com.tazzzo.serviceability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import com.tazzzo.serviceability.ServiceabilityResolution.Status;

/**
 * PR-08 review, STEP 3: {@link ServiceabilityResolution} status-shape invariants. The runtime
 * composer consumes this record blind — SERVICEABLE must carry BOTH ids, UNSERVICEABLE NEITHER,
 * and an unusable area (INACTIVE / NO_ACTIVE_ROUTE) must never leak a fulfillment location.
 */
class ServiceabilityResolutionShapeTest {

    // -- legal shapes (the four factory methods) --

    @Test void serviceable_carries_both_identifiers() {
        ServiceabilityResolution r = ServiceabilityResolution.serviceable("SA-BLR-CENTRAL", "FL-BLR-01");
        assertTrue(r.isServiceable());
        assertEquals("SA-BLR-CENTRAL", r.serviceAreaId());
        assertEquals("FL-BLR-01", r.fulfillmentLocationId());
    }

    @Test void unserviceable_carries_no_identifiers() {
        ServiceabilityResolution r = ServiceabilityResolution.unserviceable();
        assertNull(r.serviceAreaId());
        assertNull(r.fulfillmentLocationId());
    }

    @Test void inactive_carries_area_only() {
        ServiceabilityResolution r = ServiceabilityResolution.inactive("SA-BLR-CENTRAL");
        assertEquals("SA-BLR-CENTRAL", r.serviceAreaId());
        assertNull(r.fulfillmentLocationId());
    }

    @Test void no_active_route_carries_area_only() {
        ServiceabilityResolution r = ServiceabilityResolution.noActiveRoute("SA-BLR-CENTRAL");
        assertEquals("SA-BLR-CENTRAL", r.serviceAreaId());
        assertNull(r.fulfillmentLocationId());
    }

    // -- illegal shapes --

    @Test void serviceable_without_fulfillment_location_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.SERVICEABLE, "SA-BLR-CENTRAL", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.SERVICEABLE, "SA-BLR-CENTRAL", " "));
    }

    @Test void serviceable_without_area_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.SERVICEABLE, null, "FL-BLR-01"));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.SERVICEABLE, "", "FL-BLR-01"));
    }

    @Test void unserviceable_with_any_identifier_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.UNSERVICEABLE, "SA-BLR-CENTRAL", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.UNSERVICEABLE, null, "FL-BLR-01"));
    }

    @Test void inactive_leaking_fulfillment_location_rejected() {
        // An unusable area must never expose a routing target to the composer.
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.INACTIVE, "SA-BLR-CENTRAL", "FL-BLR-01"));
    }

    @Test void no_active_route_leaking_fulfillment_location_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.NO_ACTIVE_ROUTE, "SA-BLR-CENTRAL", "FL-BLR-01"));
    }

    @Test void inactive_without_area_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.INACTIVE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceabilityResolution(Status.NO_ACTIVE_ROUTE, " ", null));
    }

    @Test void null_status_rejected() {
        assertThrows(NullPointerException.class,
                () -> new ServiceabilityResolution(null, null, null));
    }
}
