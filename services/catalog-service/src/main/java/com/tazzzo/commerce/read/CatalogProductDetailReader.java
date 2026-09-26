package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerProductResolver;
import com.tazzzo.catalog.consumer.ConsumerProductResponse;
import com.tazzzo.catalog.consumer.ConsumerProjectionService;
import org.bson.Document;

import java.util.Objects;

/**
 * Default {@link CatalogProductDetailReadPort}: delegates the ENTIRE resolution core —
 * fetch, bounded merge-chain walk (PDP-MERGE-1), cycle/missing/malformed/over-bound detection,
 * and the ONE ratified {@code ConsumerEligibility} predicate — to the shared
 * {@link ConsumerProductResolver}, then projects the survivor's governed attributes through the
 * shared {@link ConsumerProjectionService}. It defines NO product-detail semantics of its own:
 * commerce PDP must not fork the meaning of "product detail" (PR-09 review, HIGH).
 *
 * <p><b>Survivor identity:</b> for a merged loser {@code A → B}, the resolver returns {@code B};
 * this reader builds facts from the SURVIVOR document, so {@code skuId}/{@code productId} are
 * {@code B} — never the requested loser {@code A}.
 *
 * <p><b>Outcome mapping:</b> resolver NOT_FOUND → {@link CatalogDetailLookup#notFound()};
 * INELIGIBLE (including a merged-to-ineligible survivor) → {@link CatalogDetailLookup#ineligible()}
 * (the PR-10 public mapper collapses INELIGIBLE into the same flat 404 as NOT_FOUND, L-5). A
 * corrupt merge chain throws {@code ConsumerFailures.Unavailable} from the resolver and PROPAGATES
 * — catalogue corruption is never disguised as a missing product. A malformed projection policy
 * throws {@code ConsumerProjectionPolicyException} and likewise propagates (operations failure,
 * PDP-1 step 7 → 503).
 *
 * <p><b>Release scope (REL-MEM-1):</b> release scopes taxonomy, not per-product eligibility, so no
 * release parameter belongs here. The frozen public route's {@code release} parameter and the
 * release-scoped vertical-reachability gate (PDP-1 step 6) are PR-10 envelope obligations.
 *
 * <p>At launch {@code skuId == productId} (the survivor {@code _id}); both are carried separately
 * so ONLY this reader changes when variants introduce SKU documents.
 */
public class CatalogProductDetailReader implements CatalogProductDetailReadPort {

    private final ConsumerProductResolver resolver;
    private final ConsumerProjectionService projection;

    public CatalogProductDetailReader(ConsumerProductResolver resolver,
                                      ConsumerProjectionService projection) {
        this.resolver = Objects.requireNonNull(resolver);
        this.projection = Objects.requireNonNull(projection);
    }

    @Override
    public CatalogDetailLookup findDetail(String skuId) {
        if (skuId == null || skuId.isBlank()) {
            return CatalogDetailLookup.notFound();
        }
        ConsumerProductResolver.Resolution resolution = resolver.resolve(skuId);
        switch (resolution.status()) {
            case NOT_FOUND -> {
                return CatalogDetailLookup.notFound();
            }
            case INELIGIBLE -> {
                return CatalogDetailLookup.ineligible();
            }
            case FOUND -> { /* build facts from the survivor below */ }
        }
        Document survivor = resolution.survivor();
        String survivorId = survivor.getString("_id");
        // Governed attribute projection (RP-1..RP-6d) — the one attribute surface.
        ConsumerProductResponse projected = projection.project(survivor);
        Document classification = survivor.get("classification", Document.class);
        return CatalogDetailLookup.found(new CatalogProductDetailFacts(
                survivorId,
                survivorId, // launch: survivor _id IS the SKU id; carried separately by design
                survivor.getString("title"),
                survivor.getString("brand_code"),
                classification == null ? null : classification.getString("vertical_id"),
                survivor.get("version") == null ? 0L : ((Number) survivor.get("version")).longValue(),
                projected.attributes()));
    }
}
