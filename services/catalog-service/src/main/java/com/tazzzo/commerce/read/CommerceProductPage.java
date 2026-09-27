package com.tazzzo.commerce.read;

/**
 * Internal result of a commerce category-products page (PR-10B). Domain-shaped — the commerce.api
 * mapper turns it into the public {@code PagedProductResponse}; commerce.read must not depend on
 * commerce.api. {@code nextCursor} is null at the end of the list.
 */
public record CommerceProductPage(String resolvedReleaseId, RuntimeProductPage page, String nextCursor) { }
