package com.tazzzo.commerce.api;

import com.tazzzo.catalog.consumer.ConsumerAttributeResponse;
import com.tazzzo.catalog.consumer.ConsumerDtos;
import com.tazzzo.commerce.api.dto.NodeDetailDto;
import com.tazzzo.commerce.api.dto.NodeDto;
import com.tazzzo.commerce.api.dto.NodeListResponse;
import com.tazzzo.commerce.api.dto.PagedProductResponse;
import com.tazzzo.commerce.api.dto.ProductAttributeDto;
import com.tazzzo.commerce.api.dto.ProductCardDto;
import com.tazzzo.commerce.api.dto.ProductDetailDto;
import com.tazzzo.commerce.api.dto.ProductImageDto;
import com.tazzzo.commerce.api.dto.ServiceAreaSummaryDto;
import com.tazzzo.commerce.api.dto.ServiceabilityResponseDto;
import com.tazzzo.commerce.read.CommerceProductPage;
import com.tazzzo.commerce.read.CommerceServiceabilityService;
import com.tazzzo.commerce.read.RuntimeProductCard;
import com.tazzzo.commerce.read.RuntimeProductDetail;
import com.tazzzo.commerce.read.RuntimeProductImage;
import com.tazzzo.commerce.read.RuntimeProductPage;
import com.tazzzo.commerce.read.RuntimeServiceArea;

import java.util.List;

/**
 * Maps internal runtime/domain values to the frozen public DTOs (PR-10B). Two disciplines:
 * <ul>
 *   <li><b>Never fabricate</b> — fields with no authoritative source (brandName, packSize, unit,
 *       offerSummary, categoryId, badges, rating, ratingCount, ETA, description, highlights,
 *       variants, legal) are passed as {@code null} and OMITTED on the wire by {@code @JsonInclude(NON_NULL)}.
 *       {@code sponsored} is the contract's explicit non-sponsored default {@code false}.</li>
 *   <li><b>Fail fast on required</b> — a required runtime value that is unexpectedly null is a
 *       server bug, thrown as {@link IllegalStateException} (mapped to 500 INTERNAL) rather than
 *       serialized as a malformed body that violates the {@code required} contract.</li>
 * </ul>
 * No fulfillmentLocationId, no raw assetKey, no stock counters ever appear — the runtime types do
 * not carry them.
 */
final class RuntimeToDtoMapper {

    private RuntimeToDtoMapper() { }

    static ProductCardDto card(RuntimeProductCard c) {
        require(c.skuId(), "skuId");
        require(c.productId(), "productId");
        require(c.title(), "name");
        if (c.stockState() == null) {
            throw new IllegalStateException("required card field missing: stockState");
        }
        return new ProductCardDto(
                c.skuId(), c.productId(), c.title(), c.brandCode(),
                null, c.thumbnailUrl(), null, null,          // brandName, thumbnailUrl, packSize, unit
                c.sellingPricePaise(), c.mrpPaise(), c.discountPercent(), c.discountAmountPaise(),
                null, null, c.verticalId(),                  // offerSummary, categoryId, verticalId
                null, null, null, false,                     // badges, rating, ratingCount, sponsored
                c.stockState(), c.lowStockRemaining(), c.maxOrderQuantity(), c.minimumOrderQuantity(),
                c.serviceable(), c.etaMinutesMin(), c.etaMinutesMax(), c.buyable());
    }

    static ProductDetailDto detail(RuntimeProductDetail d, String resolvedReleaseId, String requestId) {
        return new ProductDetailDto(
                card(d.card()),
                null,                                        // description (no source)
                null,                                        // highlights (no source)
                gallery(d.gallery()),
                attributes(d.attributes()),
                null,                                        // variants (no source)
                null,                                        // legal (no source)
                null,                                        // serviceability echo omitted; card carries it
                requireStr(resolvedReleaseId, "resolvedReleaseId"),
                requireStr(requestId, "requestId"));
    }

    static PagedProductResponse page(CommerceProductPage p, String requestId) {
        RuntimeProductPage rp = p.page();
        List<ProductCardDto> items = rp.cards().stream().map(RuntimeToDtoMapper::card).toList();
        return new PagedProductResponse(
                requireStr(p.resolvedReleaseId(), "resolvedReleaseId"),
                serviceArea(rp.serviceArea()),
                items,
                p.nextCursor(),                              // omitted at end of list
                p.nextCursor() != null,
                requireStr(requestId, "requestId"));
    }

    static com.tazzzo.commerce.api.dto.ProductBatchResponse batch(
            com.tazzzo.commerce.read.CommerceProductBatchService.Result r, String requestId) {
        return new com.tazzzo.commerce.api.dto.ProductBatchResponse(
                requireStr(r.resolvedReleaseId(), "resolvedReleaseId"),
                r.cards().stream().map(RuntimeToDtoMapper::card).toList(),
                r.missing(),
                requireStr(requestId, "requestId"));
    }

    static NodeListResponse nodes(ConsumerDtos.NodeListResponse src, String requestId) {
        List<NodeDto> items = src.items().stream()
                .map(n -> new NodeDto(n.id(), n.name())).toList();
        return new NodeListResponse(
                requireStr(src.resolvedReleaseId(), "resolvedReleaseId"), items,
                requireStr(requestId, "requestId"));
    }

    /** The by-id read: the single node of an already-mapped one-item envelope, flattened. */
    static NodeDetailDto node(NodeListResponse single) {
        if (single.items().size() != 1) {
            throw new IllegalStateException("node read must carry exactly one item, got " + single.items().size());
        }
        return new NodeDetailDto(single.items().get(0), single.resolvedReleaseId(), single.requestId());
    }

    static ServiceabilityResponseDto serviceability(CommerceServiceabilityService.View v, String requestId) {
        // PR-10B final review #4: no int32 narrowing -- the authoritative version is a long.
        return new ServiceabilityResponseDto(v.serviceable(), v.serviceAreaId(), v.serviceAreaVersion(),
                null, null, requireStr(requestId, "requestId")); // etaMin/Max omitted (no source)
    }

    private static ServiceAreaSummaryDto serviceArea(RuntimeServiceArea a) {
        return a == null ? null : new ServiceAreaSummaryDto(a.serviceAreaId(), a.serviceable());
    }

    private static List<ProductImageDto> gallery(List<RuntimeProductImage> images) {
        return images.stream()
                .map(i -> new ProductImageDto(i.url(), i.role(), i.sortOrder(), i.altText(), i.width(), i.height()))
                .toList();
    }

    private static List<ProductAttributeDto> attributes(List<ConsumerAttributeResponse> attrs) {
        return attrs.stream()
                .map(a -> new ProductAttributeDto(a.key(), a.label(), a.value(), a.unit()))
                .toList();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required card field missing: " + field);
        }
    }

    private static String requireStr(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required field missing: " + field);
        }
        return value;
    }
}
