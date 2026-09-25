package com.tazzzo.serviceability;

import com.tazzzo.commerce.contract.Pincode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical serviceability config for ONE pincode (PR-06 persistence decision, STEP 13).
 *
 * <p><b>Model:</b> one document per PIN ({@code unique(pincode)}), embedding the ordered
 * fulfillment candidates. Single-document ⇒ whole-config atomic replacement (a resolver can
 * never observe half-updated routing), supports MULTIPLE candidates from day one (launch simply
 * configures one), and needs no destructive migration for future multi-store topology.
 * {@code serviceAreaId} is the PUBLIC grouping label; it is deliberately NOT unique across
 * documents — a future service area may span many PINs without any schema change.
 *
 * <p><b>Deterministic routing (STEP 9):</b> lowest-priority ACTIVE route wins; priorities are
 * unique across the document's routes, so resolution never ties and never depends on storage
 * order. An inactive route is never selected.
 *
 * <p><b>{@code active} ownership:</b> the upsert command replaces routing config; area-level
 * {@code active} is set true on create and untouched on update — deactivation arrives as its own
 * explicit audited lifecycle command later (same pattern as Inventory/Media).
 *
 * <p>Delivery promise / ETA metadata is deliberately ABSENT: no ETA source exists yet, and a
 * fabricated constant would repeat the mobile app's hardcoded-59-minutes mistake. It will be
 * added additively as routing/config metadata when the business ratifies real values.
 */
public record ServiceArea(
        String serviceAreaId,
        String pincode,
        boolean active,
        List<ServiceabilityRoute> routes,
        long version
) {
    static final int MAX_ID = 128;
    /** Sanity ceiling — a PIN routed by more than a handful of stores is a config error. */
    static final int MAX_ROUTES = 20;

    public ServiceArea {
        if (serviceAreaId == null || serviceAreaId.isBlank() || serviceAreaId.length() > MAX_ID
                || !serviceAreaId.equals(serviceAreaId.trim())
                || serviceAreaId.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException(
                    "serviceAreaId required: non-blank, trimmed, no control chars, max " + MAX_ID);
        }
        if (!Pincode.isValid(pincode)) {
            throw new IllegalArgumentException("invalid pincode: " + pincode);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(routes, "routes required (empty list = configured but unroutable)");
        if (routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("route count exceeds sanity ceiling " + MAX_ROUTES);
        }
        routes = List.copyOf(routes);
        Set<String> locations = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        for (ServiceabilityRoute r : routes) {
            if (!locations.add(r.fulfillmentLocationId())) {
                throw new IllegalArgumentException("duplicate fulfillment location in area: "
                        + r.fulfillmentLocationId());
            }
            if (!priorities.add(r.priority())) {
                // Unique across ALL routes (not just active ones) so toggling a route active can
                // never create an ambiguous tie.
                throw new IllegalArgumentException("duplicate route priority in area: " + r.priority());
            }
        }
    }

    /** Deterministic winner: the ACTIVE route with the lowest priority number, if any. */
    public Optional<ServiceabilityRoute> activeRoute() {
        return routes.stream().filter(ServiceabilityRoute::active)
                .min(Comparator.comparingInt(ServiceabilityRoute::priority));
    }
}
