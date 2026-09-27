package com.tazzzo.commerce.read;

import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.Price;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** PR-10A UNIT: the ephemeral current-price overlay — pure, no Mongo. */
class CurrentPriceOverlayTest {

    /** A base row carrying a STALE projected price (older version, different amounts). */
    private ProductCardBaseProjection staleBase() {
        return new ProductCardBaseProjection("TZP-1", "TZP-1", "T", "BR", "TZV-1",
                PriceStatus.ACTIVE, 9999L, 12000L, "INR", "k/x.webp", 5L, 1L, 2L, 7L);
    }

    private Price activePrice(long selling, long mrp, long version) {
        return new Price("TZP-1", Currency.INR, selling, mrp, version, true,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test void active_current_price_replaces_stale_projected_amounts_and_version() {
        ProductCardBaseProjection out = CurrentPriceOverlay.withCurrentPrice(
                staleBase(), PriceLookup.of(PriceStatus.ACTIVE, activePrice(26500L, 30000L, 9L)));
        assertEquals(26500L, out.sellingPricePaise(), "fresh canonical selling price");
        assertEquals(30000L, out.mrpPaise());
        assertEquals("INR", out.currency());
        assertEquals(PriceStatus.ACTIVE, out.priceStatus());
        assertEquals(9L, out.priceVersion(), "fresh canonical price version");
        // Catalog identity + media + catalog/projection versions are preserved from the base.
        assertEquals("T", out.title());
        assertEquals("k/x.webp", out.primaryAssetKey());
        assertEquals(5L, out.catalogVersion());
        assertEquals(2L, out.mediaVersion());
        assertEquals(7L, out.projectionVersion());
    }

    @Test void missing_current_price_clears_amounts_fails_closed() {
        ProductCardBaseProjection out = CurrentPriceOverlay.withCurrentPrice(
                staleBase(), PriceLookup.missing());
        assertNull(out.sellingPricePaise(), "no stale price served");
        assertNull(out.mrpPaise());
        assertNull(out.currency());
        assertNull(out.priceVersion());
        assertEquals(PriceStatus.MISSING, out.priceStatus());
        assertEquals("T", out.title(), "identity still preserved");
    }

    @Test void inactive_and_expired_current_price_clear_amounts() {
        for (PriceStatus s : new PriceStatus[]{PriceStatus.INACTIVE, PriceStatus.EXPIRED,
                PriceStatus.NOT_YET_EFFECTIVE}) {
            Price p = new Price("TZP-1", Currency.INR, 100L, 200L, 3L, s == PriceStatus.INACTIVE ? false : true,
                    Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-02-01T00:00:00Z"));
            ProductCardBaseProjection out = CurrentPriceOverlay.withCurrentPrice(
                    staleBase(), PriceLookup.of(s, p));
            assertNull(out.sellingPricePaise(), s + " must clear amounts");
            assertEquals(s, out.priceStatus());
        }
    }

    @Test void null_inputs_rejected() {
        assertThrows(NullPointerException.class,
                () -> CurrentPriceOverlay.withCurrentPrice(null, PriceLookup.missing()));
        assertThrows(NullPointerException.class,
                () -> CurrentPriceOverlay.withCurrentPrice(staleBase(), null));
    }
}
