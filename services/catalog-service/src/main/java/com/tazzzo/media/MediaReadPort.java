package com.tazzzo.media;

/**
 * Internal read boundary for canonical media (STEP 14). Returns domain values, never Mongo
 * documents or commerce.api DTOs. Identity-based ONLY: the SKU→PRODUCT media fallback belongs
 * to the future Commerce Read composer, which knows both ids (STEP 15) — this module never
 * consults Catalog to discover a parent product.
 */
public interface MediaReadPort {

    MediaLookup findMedia(MediaOwnerType ownerType, String ownerId);
}
