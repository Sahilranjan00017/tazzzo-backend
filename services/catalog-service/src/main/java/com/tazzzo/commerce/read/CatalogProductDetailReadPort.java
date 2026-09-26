package com.tazzzo.commerce.read;

/**
 * The Catalog read boundary the PDP composer depends on (PR-09). Same pattern as
 * {@link CatalogCardReadPort} (PR-07) but with a THREE-way outcome, because a product detail
 * must distinguish "never existed" from "exists but is not consumer-eligible" internally —
 * the future public layer (PR-10) collapses INELIGIBLE into the same flat 404 as NOT_FOUND
 * (frozen contract: hidden/inactive is indistinguishable by design, rule L-5).
 *
 * <p>Returns domain values only — never Mongo documents, never commerce.api DTOs.
 */
public interface CatalogProductDetailReadPort {

    CatalogDetailLookup findDetail(String skuId);
}
