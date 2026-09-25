package com.tazzzo.commerce.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tazzzo.commerce.contract.StockState;
import java.util.List;

/**
 * Public composed ProductCard (frozen /v1 contract). Money fields are bare int64 paise on the
 * wire (ADR-002); the domain {@code Money} type is not the JSON shape. Location-derived fields
 * ({@code stockState}, {@code serviceable}, ETA) are runtime enrichment: {@code stockState} is
 * {@code UNKNOWN} and {@code serviceable} is null when no location is supplied. {@code buyable}
 * is server-derived. Optional fields are omitted (NON_NULL), never sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductCardDto(
        String skuId,
        String productId,
        String name,
        String brandCode,
        String brandName,
        String thumbnailUrl,
        String packSize,
        String unit,
        Long sellingPricePaise,
        Long mrpPaise,
        Integer discountPercent,
        Long discountAmountPaise,
        String offerSummary,
        String categoryId,
        String verticalId,
        List<String> badges,
        Integer rating,
        Integer ratingCount,
        boolean sponsored,
        StockState stockState,
        Integer lowStockRemaining,
        int maxOrderQuantity,
        int minimumOrderQuantity,
        Boolean serviceable,
        Integer etaMinutesMin,
        Integer etaMinutesMax,
        boolean buyable
) { }
