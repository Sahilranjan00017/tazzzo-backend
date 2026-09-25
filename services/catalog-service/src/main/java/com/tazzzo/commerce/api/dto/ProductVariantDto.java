package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A selectable variant of a product. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductVariantDto(String variantId, String skuId, String packSize, String unit) { }
