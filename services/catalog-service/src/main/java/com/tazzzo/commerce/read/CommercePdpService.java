package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerAdmissionGate;
import com.tazzzo.catalog.consumer.ConsumerIdentity;
import com.tazzzo.catalog.consumer.ConsumerObservability;
import com.tazzzo.catalog.consumer.ConsumerReleaseResolver;
import com.tazzzo.catalog.consumer.ConsumerTaxonomyScopeResolver;
import com.tazzzo.commerce.contract.LocationQuery;

import java.util.Objects;

/**
 * The public commerce PDP orchestration (PR-10B): resolves the release ONCE, charges PDP admission
 * (weight 1) before any read, composes the detail via the shared {@link ProductDetailRuntimeComposer}
 * (which resolves the merged-survivor identity, overlays current canonical price, and fails closed
 * on stale/inconsistent projection), then applies the release-scoped vertical reachability the PR-09
 * composer deliberately left to PR-10. NOT_FOUND, INELIGIBLE and non-reachable all collapse to the
 * same flat 404 at the public boundary (rule L-5). Corruption / data-quality propagate as typed
 * failures → 503. Read-only.
 */
public class CommercePdpService {

    public enum Status { FOUND, NOT_FOUND, INELIGIBLE }

    public record Result(Status status, String resolvedReleaseId, RuntimeProductDetail detail) { }

    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ProductDetailRuntimeComposer composer;
    private final ConsumerTaxonomyScopeResolver scopes;

    public CommercePdpService(ConsumerReleaseResolver releases, ConsumerAdmissionGate gate,
                              ProductDetailRuntimeComposer composer, ConsumerTaxonomyScopeResolver scopes) {
        this.releases = Objects.requireNonNull(releases);
        this.gate = Objects.requireNonNull(gate);
        this.composer = Objects.requireNonNull(composer);
        this.scopes = Objects.requireNonNull(scopes);
    }

    public Result detail(String productId, String explicitRelease, LocationQuery location,
                         ConsumerIdentity identity) {
        String release = releases.resolve(explicitRelease);                                   // 1
        gate.charge(ConsumerObservability.Route.COMMERCE_PDP, identity, 1);                    // 2
        RuntimeProductDetailLookup lookup =
                DomainReadGuard.guard(() -> composer.composeDetail(productId, location));       // 3-6
        switch (lookup.status()) {
            case NOT_FOUND -> {
                return new Result(Status.NOT_FOUND, release, null);
            }
            case INELIGIBLE -> {
                return new Result(Status.INELIGIBLE, release, null);
            }
            case FOUND -> { /* reachability below */ }
        }
        // 7. Release-scoped vertical reachability (PR-10 owns this; PR-09 deferred it).
        String verticalId = lookup.detail().card().verticalId();
        if (!scopes.isReachableVertical(release, verticalId)) {
            return new Result(Status.NOT_FOUND, release, null); // flat 404, indistinguishable
        }
        return new Result(Status.FOUND, release, lookup.detail());
    }
}
