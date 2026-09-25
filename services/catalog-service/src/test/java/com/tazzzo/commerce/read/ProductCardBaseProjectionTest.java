package com.tazzzo.commerce.read;

import com.tazzzo.pricing.PriceStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** PR-07 UNIT: base-card invariants, content semantics, and the no-location BLOCKER boundary. */
class ProductCardBaseProjectionTest {

    private ProductCardBaseProjection card(Long selling, Long mrp, String currency,
                                           PriceStatus status, String assetKey, long pv) {
        return new ProductCardBaseProjection("TZP-1", "TZP-1", "Atta 5kg", "AASHIRVAAD",
                "TZV-000037", status, selling, mrp, currency, assetKey, 3L, 2L, 1L, pv);
    }

    @Test void valid_active_priced_card() {
        ProductCardBaseProjection p = card(26500L, 30000L, "INR", PriceStatus.ACTIVE, "p/TZP-1/f.webp", 1);
        assertEquals(26500L, p.sellingPricePaise());
    }

    @Test void sku_and_product_id_may_diverge() {
        assertDoesNotThrow(() -> new ProductCardBaseProjection("SKU-9", "TZP-1", "T", null, null,
                PriceStatus.MISSING, null, null, null, null, 1L, null, null, 1L));
    }

    @Test void unpriced_card_requires_all_price_fields_absent() {
        assertDoesNotThrow(() -> card(null, null, null, PriceStatus.MISSING, null, 1));
        assertThrows(IllegalArgumentException.class, () -> card(100L, null, null, PriceStatus.ACTIVE, null, 1));
        assertThrows(IllegalArgumentException.class, () -> card(null, 100L, null, PriceStatus.MISSING, null, 1));
        assertThrows(IllegalArgumentException.class, () -> card(null, null, "INR", PriceStatus.MISSING, null, 1));
    }

    @Test void amounts_forbidden_unless_pricing_active() {
        for (PriceStatus s : List.of(PriceStatus.MISSING, PriceStatus.INACTIVE,
                PriceStatus.NOT_YET_EFFECTIVE, PriceStatus.EXPIRED)) {
            assertThrows(IllegalArgumentException.class, () -> card(100L, 200L, "INR", s, null, 1),
                    "amounts must be rejected for " + s);
        }
    }

    @Test void paise_invariants_enforced() {
        assertThrows(IllegalArgumentException.class, () -> card(-1L, 200L, "INR", PriceStatus.ACTIVE, null, 1));
        assertThrows(IllegalArgumentException.class, () -> card(300L, 200L, "INR", PriceStatus.ACTIVE, null, 1));
    }

    @Test void projection_version_positive() {
        assertThrows(IllegalArgumentException.class, () -> card(null, null, null, PriceStatus.MISSING, null, 0));
    }

    @Test void content_equals_ignores_projection_and_source_versions() {
        ProductCardBaseProjection a = new ProductCardBaseProjection("TZP-1", "TZP-1", "T", "B",
                "V", PriceStatus.ACTIVE, 100L, 200L, "INR", "k.webp", 3L, 2L, 1L, 1L);
        ProductCardBaseProjection b = new ProductCardBaseProjection("TZP-1", "TZP-1", "T", "B",
                "V", PriceStatus.ACTIVE, 100L, 200L, "INR", "k.webp", 99L, 88L, 77L, 42L);
        assertTrue(a.contentEquals(b), "version metadata must not affect content equality");
    }

    @Test void content_equals_detects_business_changes() {
        ProductCardBaseProjection base = card(100L, 200L, "INR", PriceStatus.ACTIVE, "k.webp", 1);
        assertFalse(base.contentEquals(card(150L, 200L, "INR", PriceStatus.ACTIVE, "k.webp", 1)));
        assertFalse(base.contentEquals(card(100L, 200L, "INR", PriceStatus.ACTIVE, "other.webp", 1)));
        assertFalse(base.contentEquals(null));
        ProductCardBaseProjection retitled = new ProductCardBaseProjection("TZP-1", "TZP-1",
                "New Title", "AASHIRVAAD", "TZV-000037", PriceStatus.ACTIVE, 100L, 200L, "INR",
                "k.webp", 3L, 2L, 1L, 1L);
        assertFalse(base.contentEquals(retitled));
    }

    @Test void technical_bounds_enforced() {
        assertThrows(IllegalArgumentException.class, () -> new ProductCardBaseProjection(
                "x".repeat(129), "TZP-1", "T", null, null, PriceStatus.MISSING,
                null, null, null, null, 1L, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ProductCardBaseProjection(
                "TZP-1", "TZP-1", "t".repeat(501), null, null, PriceStatus.MISSING,
                null, null, null, null, 1L, null, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> new ProductCardBaseProjection(
                "TZP-1", "TZP-1", "T", null, null, PriceStatus.MISSING,
                null, null, null, "a/../evil", 1L, null, null, 1L));
    }

    @Test void currency_must_be_canonical_enum_vocabulary() {
        assertThrows(IllegalArgumentException.class, () -> new ProductCardBaseProjection(
                "TZP-1", "TZP-1", "T", null, null, PriceStatus.ACTIVE,
                100L, 200L, "RUPEES", null, 1L, null, null, 1L),
                "arbitrary currency strings must fail loudly");
        assertDoesNotThrow(() -> new ProductCardBaseProjection(
                "TZP-1", "TZP-1", "T", null, null, PriceStatus.ACTIVE,
                100L, 200L, "INR", null, 1L, null, null, 1L));
    }

    @Test void BLOCKER_no_location_or_stock_concepts_in_base_model() {
        // STEP 20 hard boundary: the base projection must carry ZERO location/inventory state.
        List<String> forbidden = List.of("stock", "available", "reserved", "lowstock",
                "serviceable", "servicearea", "fulfillment", "warehouse", "storeid", "darkstore",
                "eta", "deliverypromise", "buyable", "pincode", "onhand");
        for (Class<?> c : List.of(ProductCardBaseProjection.class, CatalogCardFacts.class)) {
            for (RecordComponent rc : c.getRecordComponents()) {
                String name = rc.getName().toLowerCase(Locale.ROOT);
                for (String bad : forbidden) {
                    assertFalse(name.contains(bad),
                            c.getSimpleName() + "." + rc.getName() + " violates the no-location rule");
                }
            }
        }
    }
}
