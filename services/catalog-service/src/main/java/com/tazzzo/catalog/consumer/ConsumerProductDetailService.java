package com.tazzzo.catalog.consumer;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PDP-1 — one product by id, release-bound, rate-limited BEFORE any product read, resolved through
 * a merge chain (PDP-MERGE-1), judged by the shared predicate and the shared reachability seam,
 * projected by the shared projector.
 *
 * <p>The order of operations is the contract (PDP-1 §2.3 as ratified 2026-09-17):
 * <pre>
 *   1. resolve ONE release                     explicit absent/non-active -> 404 · pointer broken -> 503
 *   2. CHARGE Q5 weight 1 (Route.PDP)          before the FIRST product read; 429 / 503 -> zero reads
 *   3. products.find({_id}) projected to PDP_FIELDS       absent -> 404
 *   4. lifecycle == merged: follow merged_into  visited set, at most 32 hops; missing target,
 *                                              cycle, malformed pointer, exceeded bound -> 503
 *                                              (corrupt catalogue state, never a redirect)
 *   5. ConsumerEligibility.isEligible(final)   false -> 404
 *   6. classified vertical reachable (TAX-REACH-1) in the resolved release   else -> 404
 *   7. projection.project(final)               malformed policy -> 503 (RP-6d)
 *   8. { resolved_release_id, item }           the SURVIVOR's id and body; never the loser's
 * </pre>
 *
 * <p>Unknown, ineligible, bundle, holding vertical, hidden vertical, merged-into-an-ineligible
 * survivor: the SAME flat 404 (L-5). The weight of 1 is an admission policy, not a promise of one
 * physical read — a merge chain costs one read per hop, bounded, and that amplification is recorded
 * for load testing.
 */
@Service
public class ConsumerProductDetailService {

    /** PDP-MERGE-1: a valid longer chain must not be rejected; beyond this is corrupt state. */
    public static final int MAX_MERGE_HOPS = 32;

    /**
     * Exactly what the predicate, the merge walk and the projector read; nothing internal.
     * {@code classification} is projected by sub-field so provenance (release_id, confidence,
     * evidence) never enters memory.
     */
    static final List<String> PDP_FIELDS = List.of(
            "_id", "title", "brand_code", "product_type", "lifecycle",
            "classification.status", "classification.vertical_id", "attributes", "merged_into");

    private final ConsumerReleaseResolver releases;
    private final ConsumerAdmissionGate gate;
    private final ConsumerTaxonomyScopeResolver scopes;
    private final ConsumerProjectionService projection;
    private final MongoDatabase db;

    public ConsumerProductDetailService(ConsumerReleaseResolver releases,
                                        ConsumerAdmissionGate gate,
                                        ConsumerTaxonomyScopeResolver scopes,
                                        ConsumerProjectionService projection,
                                        MongoDatabase db) {
        this.releases = releases;
        this.gate = gate;
        this.scopes = scopes;
        this.projection = projection;
        this.db = db;
    }

    public ConsumerDtos.ProductDetailResponse detail(String productId, String explicitRelease,
                                                     ConsumerIdentity identity) {
        String release = releases.resolve(explicitRelease);                          // 1
        gate.charge(ConsumerObservability.Route.PDP, identity, 1);                   // 2

        Document product = fetch(productId);                                         // 3
        if (product == null) {
            throw new ConsumerFailures.NotFound("product not found: " + productId);
        }

        // 4. A merged loser resolves, internally, to the product it was merged into. The chain is
        // followed with a visited set and a bound; anything that does not resolve cleanly is corrupt
        // catalogue state (the write path cannot mint a cycle: a merged product is terminal).
        Set<String> visited = new HashSet<>();
        visited.add(productId);
        int hops = 0;
        while ("merged".equals(product.getString("lifecycle"))) {
            Object target = product.get("merged_into");
            if (!(target instanceof String next) || next.isBlank()) {
                throw new ConsumerFailures.Unavailable("merge pointer corrupt");
            }
            if (!visited.add(next)) {
                throw new ConsumerFailures.Unavailable("merge chain cyclic");
            }
            if (++hops > MAX_MERGE_HOPS) {
                throw new ConsumerFailures.Unavailable("merge chain exceeds " + MAX_MERGE_HOPS + " hops");
            }
            product = fetch(next);
            if (product == null) {
                throw new ConsumerFailures.Unavailable("merge target missing");
            }
        }

        if (!ConsumerEligibility.isEligible(product)) {                              // 5
            throw new ConsumerFailures.NotFound("product not consumer-eligible");
        }
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

    /** One point read by primary key, narrowly projected. Never a probe, never a scan. */
    private Document fetch(String productId) {
        return db.getCollection("products")
                .find(Filters.eq("_id", productId))
                .projection(Projections.include(PDP_FIELDS))
                .first();
    }
}
