package com.tazzzo.commerce.read;

import java.util.Optional;

/**
 * The Catalog read boundary the projection builder depends on (PR-07, STEP 13). Returns facts
 * ONLY for consumer-eligible products; an ineligible/unknown SKU is empty. Introduced because
 * Catalog exposes no dedicated card port — this is the smallest read abstraction, and its default
 * implementation reuses the ONE ratified eligibility predicate rather than redefining truth.
 */
public interface CatalogCardReadPort {

    Optional<CatalogCardFacts> findEligibleCard(String skuId);
}
