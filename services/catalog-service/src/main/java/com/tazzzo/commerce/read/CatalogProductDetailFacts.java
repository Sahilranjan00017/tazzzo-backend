package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerAttributeResponse;

import java.util.List;

/**
 * The consumer-safe Catalog facts one product DETAIL is built from (PR-09). Sourced ONLY from
 * products that pass {@code ConsumerEligibility}; attributes are the POLICY-PROJECTED ordered
 * list from {@code ConsumerProjectionService} — the single ratified attribute-governance surface
 * (per-vertical opt-in, display labels, paired units, no admin/provenance fields) — deliberately
 * REUSED rather than re-modelled, so no second attribute taxonomy can drift.
 *
 * <p>{@code skuId}/{@code productId} are carried separately even though equal at launch — the
 * same variant seam as {@link CatalogCardFacts}. Deliberately ABSENT because the product
 * document has no authoritative source for them (never fabricated): description, brandName,
 * packSize/unit as dedicated fields (they surface as governed ATTRIBUTES when the vertical's
 * policy opts them in), manufacturer, country of origin, ingredients, nutrition, legal text,
 * seller, rating, badges.
 */
public record CatalogProductDetailFacts(
        String skuId,
        String productId,
        String title,
        String brandCode,
        String verticalId,
        long catalogVersion,
        List<ConsumerAttributeResponse> attributes
) {
    public CatalogProductDetailFacts {
        if (skuId == null || skuId.isBlank()) throw new IllegalArgumentException("skuId required");
        if (productId == null || productId.isBlank()) throw new IllegalArgumentException("productId required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        if (catalogVersion < 0) throw new IllegalArgumentException("catalogVersion must be >= 0");
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
}
