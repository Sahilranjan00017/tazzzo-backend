package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerAttributeResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The INTERNAL runtime product detail (PR-09): the PR-08 {@link RuntimeProductCard} (identity,
 * price, discount, stock, serviceability, buyability — ONE set of commerce rules, never forked)
 * ⊕ policy-projected Catalog attributes ⊕ the resolved media gallery. Deliberately shaped like
 * the frozen contract's {@code ProductDetail = allOf(ProductCard) + detail additions} so the
 * PR-10 mapper is a flattening, not a re-derivation.
 *
 * <p>This is NOT the public {@code ProductDetailDto} — the frozen DAG says
 * {@code commerce.api → commerce.read}, never the reverse. Public-envelope concerns
 * (resolvedReleaseId, requestId, release-scoped vertical reachability, serviceability echo
 * block, sponsored=false) belong to PR-10.
 *
 * <p><b>No fabrication (STEP 34):</b> fields with no authoritative source are ABSENT from this
 * model, not defaulted — description, brandName, packSize/unit as dedicated fields (they appear
 * inside {@code attributes} when the vertical's projection policy opts them in), manufacturer,
 * country of origin, legal text, seller, rating/ratingCount, badges, offerSummary, variants
 * (variant_group_id exists in Catalog but no consumer variant surface does), ETA
 * (null inside the card — no ratified source).
 *
 * <p><b>Leak boundary:</b> no fulfillmentLocationId, no inventory counters, no raw assetKey,
 * no Mongo documents, no audit/event ids — enforced by construction and reflection-guard
 * tested like PR-08's runtime models.
 */
public record RuntimeProductDetail(
        RuntimeProductCard card,
        List<ConsumerAttributeResponse> attributes,
        String primaryImageUrl,
        List<RuntimeProductImage> gallery
) {
    public RuntimeProductDetail {
        Objects.requireNonNull(card, "card required");
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
        gallery = gallery == null ? List.of() : List.copyOf(gallery);

        // Deterministic gallery: strictly ascending sortOrder, no duplicate URLs (unique
        // assetKeys upstream make duplicates impossible unless composition is buggy — fail loud).
        int prevOrder = -1;
        Set<String> urls = new HashSet<>();
        for (RuntimeProductImage image : gallery) {
            if (image.sortOrder() <= prevOrder) {
                throw new IllegalArgumentException("gallery must be strictly ordered by sortOrder");
            }
            prevOrder = image.sortOrder();
            if (!urls.add(image.url())) {
                throw new IllegalArgumentException("duplicate gallery url");
            }
        }
        if (primaryImageUrl != null && !urls.contains(primaryImageUrl)) {
            // the primary is by definition a member of the chosen set, so it must be in the gallery
            throw new IllegalArgumentException("primaryImageUrl must be one of the gallery urls");
        }
    }
}
