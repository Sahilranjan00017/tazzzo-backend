package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Public bounded batch product read ({@code GET /v1/products:batch}). {@code items} are the SAME ProductCard the
 * single-id read embeds in its ProductDetail, in request order (first occurrence of a duplicated id). {@code missing}
 * lists, in request order, every requested id that has no card, with no reason: an unknown id and a non-public one
 * are indistinguishable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductBatchResponse(
        String resolvedReleaseId,
        List<ProductCardDto> items,
        List<String> missing,
        String requestId
) { }
