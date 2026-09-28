package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceReadPort;
import com.tazzzo.pricing.PriceStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PR-12C — the ONE authoritative read seam a customer-owned SKU list (the cart) uses to obtain
 * CURRENT commerce truth. It composes ONLY existing authorities — the ratified catalog eligibility
 * predicate ({@link CatalogCardReadPort}), the derived base projection, CURRENT canonical Pricing
 * ({@link CurrentPriceOverlay}) and the unchanged {@link ProductCardRuntimeEnricher} — so there is
 * exactly one buyable/stock/serviceability algorithm in the system and the cart never forks it.
 *
 * <p>Cost: at most one point read per SKU for eligibility (bounded by the cart's own item cap),
 * one batched base read, one batched price read, one serviceability resolution and one batched
 * inventory read. No cache is introduced.
 *
 * <p>Infrastructure failures surface as {@link CommerceReadUnavailableException} (via
 * {@link DomainReadGuard}); an outage is never disguised as "not visible" or "out of stock".
 */
public class CommerceSkuBatchReader {

    private final CatalogCardReadPort catalog;
    private final ProductCardBaseReadPort bases;
    private final PriceReadPort prices;
    private final ProductCardRuntimeEnricher enricher;

    public CommerceSkuBatchReader(CatalogCardReadPort catalog, ProductCardBaseReadPort bases, PriceReadPort prices,
                                  ProductCardRuntimeEnricher enricher) {
        this.catalog = Objects.requireNonNull(catalog);
        this.bases = Objects.requireNonNull(bases);
        this.prices = Objects.requireNonNull(prices);
        this.enricher = Objects.requireNonNull(enricher);
    }

    /** Whether the SKU is currently customer-visible under the canonical eligibility predicate. */
    public boolean isCustomerVisible(String skuId) {
        return DomainReadGuard.guard(() -> catalog.findEligibleCard(skuId).isPresent());
    }

    /**
     * Current runtime cards for the customer-VISIBLE subset of {@code skuIds}. A SKU that is not
     * visible (unknown, hidden, ineligible) is simply ABSENT from the result. Input order of the
     * present SKUs is preserved. The caller must bound {@code skuIds} (enricher page cap = 50).
     */
    public Map<String, RuntimeProductCard> readCurrent(Collection<String> skuIds, LocationQuery location) {
        Objects.requireNonNull(location, "location required");
        LinkedHashSet<String> distinct = new LinkedHashSet<>(skuIds);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return DomainReadGuard.guard(() -> {
            Map<String, CatalogCardFacts> visible = new LinkedHashMap<>();
            for (String sku : distinct) {
                Optional<CatalogCardFacts> facts = catalog.findEligibleCard(sku);
                facts.ifPresent(f -> visible.put(sku, f));
            }
            if (visible.isEmpty()) {
                return Map.<String, RuntimeProductCard>of();
            }
            Map<String, ProductCardBaseProjection> baseBySku = new LinkedHashMap<>();
            for (ProductCardBaseProjection b : bases.findBySkuIds(visible.keySet())) {
                baseBySku.put(b.skuId(), b);
            }
            Map<String, PriceLookup> priceBySku = prices.findCurrentPrices(visible.keySet(), Currency.INR);

            List<ProductCardBaseProjection> overlaid = new ArrayList<>(visible.size());
            for (Map.Entry<String, CatalogCardFacts> e : visible.entrySet()) {
                ProductCardBaseProjection base = baseBySku.getOrDefault(e.getKey(), failClosedBase(e.getValue()));
                overlaid.add(CurrentPriceOverlay.withCurrentPrice(base,
                        priceBySku.getOrDefault(e.getKey(), PriceLookup.missing())));
            }
            RuntimeProductPage page = enricher.enrichPage(overlaid, location);
            Map<String, RuntimeProductCard> out = new LinkedHashMap<>();
            for (RuntimeProductCard card : page.cards()) {
                out.put(card.skuId(), card);
            }
            return out;
        });
    }

    /** Projection freshness gap: identity from FRESH catalog facts, no image, price applied by overlay. */
    private static ProductCardBaseProjection failClosedBase(CatalogCardFacts f) {
        return new ProductCardBaseProjection(f.skuId(), f.productId(), f.title(), f.brandCode(), f.verticalId(),
                PriceStatus.MISSING, null, null, null, null, f.catalogVersion(), null, null, 1L);
    }
}
