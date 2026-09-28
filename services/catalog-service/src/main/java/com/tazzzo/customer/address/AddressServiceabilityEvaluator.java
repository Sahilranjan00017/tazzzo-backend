package com.tazzzo.customer.address;

import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.serviceability.PublicServiceability;
import com.tazzzo.serviceability.ServiceabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PR-12B — the ONE seam between {@code customer.address} and the existing serviceability domain.
 * Reuses {@link ServiceabilityService#resolvePublic} UNCHANGED — the exact same read the public
 * {@code /v1/serviceability} commerce endpoint uses, which already strips the INTERNAL
 * {@code fulfillmentLocationId}. That read also carries the public {@code serviceAreaId}/
 * {@code serviceAreaVersion}; this evaluator deliberately consumes ONLY the {@code serviceable}
 * boolean, because the address projection is intentionally narrower. No parallel serviceability
 * engine is invented here.
 *
 * <p><b>PIN only, by design.</b> The serviceability domain's read port is explicitly
 * source-agnostic about location precedence and, as of this PR, resolves ONLY by
 * {@link Pincode} — there is no lat/lng input path in {@code ServiceabilityService} today. This
 * evaluator therefore ALWAYS evaluates by {@code postalCode}, never lat/lng, even though an
 * address may also carry valid coordinates (persisted for future use, per the address domain's own
 * contract — see {@code Address} field docs). This is a truthful reflection of the CURRENT
 * serviceability contract, not an invented precedence rule.
 *
 * <p><b>Three-state result, never collapsed.</b> {@link Result#UNKNOWN} is distinct from
 * {@link Result#UNSERVICEABLE}: a valid PIN that is genuinely outside coverage is
 * {@code UNSERVICEABLE}; a serviceability-dependency failure (e.g. a transient Mongo read error)
 * is {@code UNKNOWN} — the address response still succeeds (mission §25: never lie and say
 * unserviceable when the truth is "cannot currently tell").
 *
 * <p><b>Cost.</b> {@code resolvePublic} is a single local Mongo point lookup (no external HTTP
 * call) — evaluating per-address for a list bounded at {@link AddressLimitProperties}'s small
 * launch limit (10) is cheap; no batching/caching is introduced here.
 */
@Component
public class AddressServiceabilityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AddressServiceabilityEvaluator.class);

    enum Result { SERVICEABLE, UNSERVICEABLE, UNKNOWN }

    private final ServiceabilityService serviceability;

    public AddressServiceabilityEvaluator(ServiceabilityService serviceability) {
        this.serviceability = serviceability;
    }

    Result evaluate(String postalCode) {
        try {
            PublicServiceability resolution = serviceability.resolvePublic(new Pincode(postalCode));
            return resolution.serviceable() ? Result.SERVICEABLE : Result.UNSERVICEABLE;
        } catch (RuntimeException e) {
            log.warn("address_serviceability_evaluation_failed type={}", e.getClass().getSimpleName());
            return Result.UNKNOWN;
        }
    }
}
