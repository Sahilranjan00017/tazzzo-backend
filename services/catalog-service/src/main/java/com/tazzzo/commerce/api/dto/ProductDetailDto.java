package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/**
 * Public ProductDetail (frozen /v1 contract). The card fields are unwrapped so the JSON is flat
 * (matching the OpenAPI {@code allOf(ProductCard, ...)} composition) — a detail and a list item
 * never drift. Detail-only fields follow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductDetailDto(
        @JsonUnwrapped ProductCardDto card,
        String description,
        List<String> highlights,
        List<ProductImageDto> gallery,
        List<ProductAttributeDto> attributes,
        List<ProductVariantDto> variants,
        LegalInformationDto legal,
        ServiceabilityResponseDto serviceability,
        String resolvedReleaseId,
        String requestId
) { }
