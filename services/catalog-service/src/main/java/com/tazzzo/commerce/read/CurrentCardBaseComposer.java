package com.tazzzo.commerce.read;

import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceReadPort;
import com.tazzzo.pricing.PriceStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * PR-12C hardening — the ONE place a list of customer-VISIBLE catalog facts becomes base
 * projections carrying CURRENT canonical price, ready for {@link ProductCardRuntimeEnricher}:
 * base projection lookup, fail-closed base for a projection freshness gap, current price overlay.
 * Shared by {@link CommerceListService} and {@link CommerceSkuBatchReader} so the public list and
 * any customer-owned SKU list can never evolve two subtly different fail-closed identities.
 *
 * <p>Domain-neutral: knows nothing about carts, lists or pagination, and derives NO stock,
 * serviceability or buyable state — that stays solely in {@link ProductCardRuntimeEnricher}.
 * Callers decide visibility (eligibility) and pass only visible facts; input order is preserved.
 */
final class CurrentCardBaseComposer {

    /** Fresh catalog facts cannot satisfy the projection's technical bounds (e.g. over-long title). */
    static final class FactsOutOfBoundsException extends RuntimeException {
        FactsOutOfBoundsException(String skuId, Throwable cause) {
            super("projection facts over bounds for " + skuId, cause);
        }
    }

    private final ProductCardBaseReadPort bases;
    private final PriceReadPort prices;

    CurrentCardBaseComposer(ProductCardBaseReadPort bases, PriceReadPort prices) {
        this.bases = Objects.requireNonNull(bases);
        this.prices = Objects.requireNonNull(prices);
    }

    /**
     * @param skuIds       the customer-VISIBLE SKUs, in the order the result must keep
     * @param factsIfGap   fresh catalog facts for a SKU, asked ONLY when its projection row is absent
     * @param baseMissing  invoked once per SKU whose projection row is absent (freshness gap signal)
     * @throws FactsOutOfBoundsException when a fail-closed base cannot be built for a gap SKU
     */
    List<ProductCardBaseProjection> compose(List<String> skuIds, Function<String, CatalogCardFacts> factsIfGap,
                                            Consumer<String> baseMissing) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        Map<String, ProductCardBaseProjection> baseBySku = new LinkedHashMap<>();
        for (ProductCardBaseProjection b : bases.findBySkuIds(skuIds)) {
            baseBySku.put(b.skuId(), b);
        }
        Map<String, PriceLookup> priceBySku =
                DomainReadGuard.guard(() -> prices.findCurrentPrices(skuIds, Currency.INR));

        List<ProductCardBaseProjection> out = new ArrayList<>(skuIds.size());
        for (String sku : skuIds) {
            ProductCardBaseProjection base = baseBySku.get(sku);
            if (base == null) {
                baseMissing.accept(sku);
                base = failClosedBase(factsIfGap.apply(sku));
            }
            out.add(CurrentPriceOverlay.withCurrentPrice(base, priceBySku.getOrDefault(sku, PriceLookup.missing())));
        }
        return out;
    }

    /**
     * Fail-closed stand-in for a visible product whose projection row is missing: identity from
     * FRESH catalog facts, {@code PriceStatus.MISSING} (no price → not buyable), no media key. Never
     * priced here — a freshness gap must not sell (the current-price overlay still applies afterwards).
     */
    static ProductCardBaseProjection failClosedBase(CatalogCardFacts f) {
        try {
            return new ProductCardBaseProjection(f.skuId(), f.productId(), f.title(), f.brandCode(),
                    f.verticalId(), PriceStatus.MISSING, null, null, null, null, f.catalogVersion(), null, null, 1L);
        } catch (IllegalArgumentException e) {
            throw new FactsOutOfBoundsException(f.skuId(), e);
        }
    }
}
