package com.tazzzo.commerce.read;

import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.pricing.PriceReadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * predicate ({@link CatalogCardReadPort}), the shared {@link CurrentCardBaseComposer} (base projection,
 * fail-closed base, CURRENT canonical Pricing overlay — the SAME one the public list uses) and the unchanged {@link ProductCardRuntimeEnricher} — so there is
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

    private static final Logger log = LoggerFactory.getLogger(CommerceSkuBatchReader.class);

    private final CatalogCardReadPort catalog;
    private final CurrentCardBaseComposer composer;
    private final ProductCardRuntimeEnricher enricher;

    public CommerceSkuBatchReader(CatalogCardReadPort catalog, ProductCardBaseReadPort bases, PriceReadPort prices,
                                  ProductCardRuntimeEnricher enricher) {
        this.catalog = Objects.requireNonNull(catalog);
        this.composer = new CurrentCardBaseComposer(bases, prices);
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
            List<ProductCardBaseProjection> overlaid = composer.compose(List.copyOf(visible.keySet()), visible::get,
                    sku -> log.warn("commerce_sku_batch_base_missing sku={}", sku));
            RuntimeProductPage page = enricher.enrichPage(overlaid, location);
            Map<String, RuntimeProductCard> out = new LinkedHashMap<>();
            for (RuntimeProductCard card : page.cards()) {
                out.put(card.skuId(), card);
            }
            return out;
        });
    }
}
