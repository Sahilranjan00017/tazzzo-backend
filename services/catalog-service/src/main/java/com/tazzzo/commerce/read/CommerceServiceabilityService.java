package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerAdmissionGate;
import com.tazzzo.catalog.consumer.ConsumerIdentity;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.serviceability.PublicServiceability;
import com.tazzzo.serviceability.ServiceabilityService;

import java.util.Objects;

/**
 * The public commerce serviceability read (PR-10B). Charges admission (weight 1), then resolves the
 * PIN through the serviceability domain's {@link ServiceabilityService#resolvePublic} — which
 * carries the authoritative {@code serviceAreaVersion} and NEVER the internal fulfillmentLocationId.
 * Returns a commerce.read view so commerce.api never imports the serviceability domain (DAG).
 * ETA is omitted (no authoritative source). Read-only.
 */
public class CommerceServiceabilityService {

    /** Commerce-facing serviceability view; the mapper turns it into ServiceabilityResponseDto. */
    public record View(boolean serviceable, String serviceAreaId, Long serviceAreaVersion) { }

    private final ConsumerAdmissionGate gate;
    private final ServiceabilityService serviceability;

    public CommerceServiceabilityService(ConsumerAdmissionGate gate, ServiceabilityService serviceability) {
        this.gate = Objects.requireNonNull(gate);
        this.serviceability = Objects.requireNonNull(serviceability);
    }

    public View resolve(Pincode pin, ConsumerIdentity identity) {
        gate.charge(ConsumerObservability.Route.COMMERCE_SERVICEABILITY, identity, 1);
        PublicServiceability r = serviceability.resolvePublic(pin);
        return new View(r.serviceable(), r.serviceAreaId(), r.serviceAreaVersion());
    }
}
