package com.tazzzo.commerce.read;

import com.tazzzo.pricing.Price;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceStatus;

/**
 * Ephemeral price overlay (PR-10A) — the seam PR-10B uses so PUBLIC serving never shows a stale
 * projected price. Given a GLOBAL base projection (whose price fields may lag under ADR-005
 * bounded-stale browse) and the CURRENT canonical price for that SKU, it produces a NEW in-memory
 * base projection carrying the current canonical price, which then flows through the unchanged
 * {@link ProductCardRuntimeEnricher} — so PR-08 remains the ONE discount/buyable/stock authority.
 *
 * <p><b>Never persisted:</b> the returned projection is a request-time value; nothing writes it
 * back to {@code product_card_base}. Catalog identity and the media asset key stay from the base
 * snapshot (bounded-stale browse is acceptable for those; media may degrade safely), while price
 * is replaced with fresh canonical truth.
 *
 * <p><b>Fail closed:</b> for any non-{@code ACTIVE} current price (MISSING/INACTIVE/EXPIRED/
 * NOT_YET_EFFECTIVE) the amounts are cleared and the canonical status recorded, so the enriched
 * card is not buyable. Legacy/offer price is never consulted.
 */
public final class CurrentPriceOverlay {

    private CurrentPriceOverlay() { }

    public static ProductCardBaseProjection withCurrentPrice(ProductCardBaseProjection base,
                                                             PriceLookup current) {
        java.util.Objects.requireNonNull(base, "base required");
        java.util.Objects.requireNonNull(current, "current price lookup required");
        boolean active = current.status() == PriceStatus.ACTIVE && current.price() != null;
        Price p = active ? current.price() : null;
        return new ProductCardBaseProjection(
                base.skuId(), base.productId(), base.title(), base.brandCode(), base.verticalId(),
                current.status(),
                active ? p.sellingPricePaise() : null,
                active ? p.mrpPaise() : null,
                active ? p.currency().name() : null,
                base.primaryAssetKey(),
                base.catalogVersion(),
                active ? p.version() : null,
                base.mediaVersion(),
                base.projectionVersion());
    }
}
