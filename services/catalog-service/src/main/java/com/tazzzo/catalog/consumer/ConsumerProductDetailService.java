package com.tazzzo.catalog.consumer;

import org.bson.Document;
import org.springframework.stereotype.Service;

/**
 * PDP-1 — one product by id, release-bound, rate-limited BEFORE any product read, resolved through
 * a merge chain (PDP-MERGE-1), judged by the shared predicate, projected by the shared projector.
 *
 * <p>The order of operations is the contract (PDP-1 §2.3 as ratified 2026-09-17):
 * <pre>
 *   1. resolve ONE release                     explicit absent/non-active -> 404 · pointer broken -> 503
 *   2. CHARGE Q5 weight 1 (Route.PDP)          before the FIRST product read; 429 / 503 -> zero reads
 *   3-5. {@link ConsumerProductResolver}       fetch + bounded merge walk + shared eligibility
 *                                              (absent -> 404 · ineligible/merged-to-ineligible -> 404 ·
 *                                              cycle/missing/malformed/over-bound chain -> 503)
 *   6. classified vertical reachable (TAX-REACH-1) in the resolved release   else -> 404
 *   7. projection.project(survivor)            malformed policy -> 503 (RP-6d)
 *   8. { resolved_release_id, item }           the SURVIVOR's id and body; never the loser's
 * </pre>
 *
 * <p>The fetch / merge walk / eligibility now live in {@link ConsumerProductResolver} so the
 * INTERNAL commerce PDP composer reuses EXACTLY this resolution — there is no second meaning of
 * "product detail". Reachability (step 6) and projection (step 7) stay HERE, in this exact order,
 * because reachability must gate BEFORE projection (a non-reachable product is a flat 404 that
 * never projects). Unknown, ineligible, bundle, holding vertical, hidden vertical, merged-into-an-
 * ineligible survivor: the SAME flat 404 (L-5). The weight of 1 is an admission policy, not a
 * promise of one physical read — a merge chain costs one read per hop, bounded and recorded.
 */
@Service
public class ConsumerProductDetailService {

    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ConsumerTaxonomyScopeResolver scopes;
    private final ConsumerProjectionService projection;
    private final ConsumerProductResolver resolver;

    public ConsumerProductDetailService(ConsumerReleaseResolver releases,
                                        ConsumerAdmissionGate gate,
                                        ConsumerTaxonomyScopeResolver scopes,
                                        ConsumerProjectionService projection,
                                        ConsumerProductResolver resolver) {
        this.releases = releases;
        this.gate = gate;
        this.scopes = scopes;
        this.projection = projection;
        this.resolver = resolver;
    }

    public ConsumerDtos.ProductDetailResponse detail(String productId, String explicitRelease,
                                                     ConsumerIdentity identity) {
        String release = releases.resolve(explicitRelease);                          // 1
        gate.charge(ConsumerObservability.Route.PDP, identity, 1);                   // 2

        ConsumerProductResolver.Resolution resolution = resolver.resolve(productId); // 3-5
        switch (resolution.status()) {
            case NOT_FOUND -> throw new ConsumerFailures.NotFound("product not found: " + productId);
            case INELIGIBLE -> throw new ConsumerFailures.NotFound("product not consumer-eligible");
            case FOUND -> { /* proceed */ }
        }
        Document product = resolution.survivor();

        Document classification = product.get("classification", Document.class);
        String verticalId = classification == null ? null : classification.getString("vertical_id");
        if (!scopes.isReachableVertical(release, verticalId)) {                      // 6
            throw new ConsumerFailures.NotFound("product vertical not consumer-reachable");
        }

        ConsumerProductResponse item;                                                // 7
        try {
            item = projection.project(product);
        } catch (ConsumerProjectionPolicyException e) {
            throw new ConsumerFailures.Unavailable("projection policy misconfigured");
        }
        return new ConsumerDtos.ProductDetailResponse(release, item);                // 8
    }
}
