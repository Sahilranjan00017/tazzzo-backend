package com.tazzzo.serviceability;

/**
 * Result of resolving a validated PIN (PR-06, STEP 10). Distinct states — never collapsed to a
 * boolean, because the composition layer must treat them differently:
 * <ul>
 *   <li>{@code SERVICEABLE} — active area + active route; carries the public serviceAreaId and
 *       the INTERNAL fulfillmentLocationId (for Inventory reads only, never for clients).</li>
 *   <li>{@code UNSERVICEABLE} — a VALID pin with no configured area: the normal outside-coverage
 *       answer.</li>
 *   <li>{@code INACTIVE} — an area exists but is switched off.</li>
 *   <li>{@code NO_ACTIVE_ROUTE} — an active area with no usable route: a CONFIGURATION error
 *       signal (observability/data quality), not silently routed and not plain unserviceable.</li>
 * </ul>
 * An INVALID pin never reaches resolution — {@code Pincode} construction rejects it first.
 *
 * <p><b>DECIDED (PR-06 review, STEP 7):</b> under the one-row-per-COVERED-PIN model, absence of a
 * row IS the business meaning "outside configured coverage" — so an absent PIN returns
 * {@code UNSERVICEABLE}, and a separate {@code MISSING_CONFIGURATION} status is deliberately NOT
 * used for absent PINs (there is no way, and no need, to distinguish "never covered" from
 * "accidentally deleted" at read time; coverage completeness is an operational/data-quality
 * concern). The config-error state that IS distinguishable — an active area whose routes are all
 * inactive — has its own status: {@code NO_ACTIVE_ROUTE}.
 */
public record ServiceabilityResolution(Status status, String serviceAreaId,
                                       String fulfillmentLocationId) {

    public enum Status { SERVICEABLE, UNSERVICEABLE, INACTIVE, NO_ACTIVE_ROUTE }

    public static ServiceabilityResolution serviceable(String serviceAreaId, String fulfillmentLocationId) {
        return new ServiceabilityResolution(Status.SERVICEABLE, serviceAreaId, fulfillmentLocationId);
    }

    public static ServiceabilityResolution unserviceable() {
        return new ServiceabilityResolution(Status.UNSERVICEABLE, null, null);
    }

    public static ServiceabilityResolution inactive(String serviceAreaId) {
        return new ServiceabilityResolution(Status.INACTIVE, serviceAreaId, null);
    }

    public static ServiceabilityResolution noActiveRoute(String serviceAreaId) {
        return new ServiceabilityResolution(Status.NO_ACTIVE_ROUTE, serviceAreaId, null);
    }

    public boolean isServiceable() {
        return status == Status.SERVICEABLE;
    }
}
