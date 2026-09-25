package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Cursor-paginated ProductCard page (frozen /v1 contract, ADR-009). {@code nextCursor} is
 * OMITTED at end of list; {@code hasMore} is derived as {@code nextCursor != null}. {@code cursor}
 * is opaque — clients never parse it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedProductResponse(
        String resolvedReleaseId,
        ServiceAreaSummaryDto serviceArea,
        List<ProductCardDto> items,
        String nextCursor,
        boolean hasMore,
        String requestId
) { }
