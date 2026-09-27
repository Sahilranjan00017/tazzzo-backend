package com.tazzzo.commerce.read;

import com.tazzzo.catalog.consumer.ConsumerAttributeResponse;
import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.contract.StockState;
import com.tazzzo.inventory.InventoryLookup;
import com.tazzzo.inventory.InventoryReadPort;
import com.tazzzo.inventory.InventoryRecord;
import com.tazzzo.media.InvalidMediaException;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaLookup;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.MediaReadPort;
import com.tazzzo.media.MediaSet;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.pricing.PriceStatus;
import com.tazzzo.serviceability.ServiceabilityReadPort;
import com.tazzzo.serviceability.ServiceabilityResolution;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** PR-09 UNIT: PDP composition matrix — pure, stub-driven, no Mongo. */
class ProductDetailComposerTest {

    private static final Pincode PIN = new Pincode("560047");
    private static final LocationQuery AT_PIN = LocationQuery.ofPin(PIN);
    private static final String BASE_URL = "https://media.test.example";

    // ---- stubs ---------------------------------------------------------------------

    private final CatalogProductDetailReadPort neverCatalog =
            sku -> { throw new AssertionError("catalog detail must not be read"); };
    private final ProductCardBaseReadPort neverBase = new ProductCardBaseReadPort() {
        @Override public Optional<ProductCardBaseProjection> findBySku(String skuId) {
            throw new AssertionError("base row must not be read");
        }
        @Override public List<ProductCardBaseProjection> findBySkuIds(java.util.Collection<String> skuIds) {
            throw new AssertionError("base rows must not be read");
        }
    };
    private final ServiceabilityReadPort neverServiceability =
            pin -> { throw new AssertionError("serviceability must not be called"); };
    private final InventoryReadPort neverInventory =
            (sku, loc) -> { throw new AssertionError("inventory must not be called"); };
    private final MediaReadPort neverMedia =
            (type, id) -> { throw new AssertionError("media must not be read"); };
    private final ServiceabilityReadPort serviceableAt =
            pin -> ServiceabilityResolution.serviceable("SA-1", "FL-1");

    private CatalogProductDetailFacts facts(List<ConsumerAttributeResponse> attributes) {
        return new CatalogProductDetailFacts("TZP-1", "TZP-1", "Detail T", "BR", "TZV-1", 3L, attributes);
    }

    private CatalogProductDetailReadPort foundCatalog(CatalogProductDetailFacts f) {
        return sku -> CatalogDetailLookup.found(f);
    }

    private ProductCardBaseReadPort baseRow(ProductCardBaseProjection row) {
        return new ProductCardBaseReadPort() {
            @Override public Optional<ProductCardBaseProjection> findBySku(String skuId) {
                return Optional.ofNullable(row);
            }
            @Override public List<ProductCardBaseProjection> findBySkuIds(java.util.Collection<String> skuIds) {
                throw new AssertionError("PDP must use the single-SKU read");
            }
        };
    }

    private ProductCardBaseProjection pricedBase() {
        return new ProductCardBaseProjection("TZP-1", "TZP-1", "Detail T", "BR", "TZV-1",
                PriceStatus.ACTIVE, 26500L, 30000L, "INR", "p/x/primary.webp", 3L, 1L, 1L, 1L);
    }

    /** A priced base row at a given catalog version, for freshness-gate tests. */
    private ProductCardBaseProjection baseAt(String title, long catalogVersion) {
        return new ProductCardBaseProjection("TZP-1", "TZP-1", title, "BR", "TZV-1",
                PriceStatus.ACTIVE, 26500L, 30000L, "INR", null, catalogVersion, 1L, null, 1L);
    }

    private CatalogProductDetailFacts factsV(String title, long catalogVersion) {
        return new CatalogProductDetailFacts("TZP-1", "TZP-1", title, "BR", "TZV-1", catalogVersion, List.of());
    }

    private InventoryReadPort stock(long onHand, long reserved, long threshold, long cap, boolean active) {
        return (sku, loc) -> {
            InventoryRecord r = new InventoryRecord(sku, loc, onHand, reserved, threshold, cap, 1, active);
            return active ? InventoryLookup.of(InventoryLookup.Status.PRESENT, r)
                    : InventoryLookup.of(InventoryLookup.Status.INACTIVE, r);
        };
    }

    private MediaAsset asset(String id, String key, ImageRole role, int order) {
        return new MediaAsset(id, key, role, order, "alt " + id, 800, 600, "image/webp");
    }

    private MediaReadPort skuMedia(MediaSet set) {
        return (type, id) -> type == MediaOwnerType.SKU && set != null
                ? MediaLookup.of(MediaLookup.Status.PRESENT, set)
                : MediaLookup.missing();
    }

    private ProductDetailRuntimeComposer composer(CatalogProductDetailReadPort catalog,
                                                  ProductCardBaseReadPort base,
                                                  ServiceabilityReadPort svc,
                                                  InventoryReadPort inv,
                                                  MediaReadPort media,
                                                  MediaUrlResolver resolver) {
        return new ProductDetailRuntimeComposer(catalog, base,
                new ProductCardRuntimeEnricher(svc, inv, resolver), media, resolver);
    }

    private ProductDetailRuntimeComposer happyComposer(MediaSet mediaSet) {
        return composer(foundCatalog(facts(List.of())), baseRow(pricedBase()),
                serviceableAt, stock(50, 0, 3, 10, true), skuMedia(mediaSet), MediaUrlResolver.of(BASE_URL));
    }

    // ---- input validation ------------------------------------------------------------

    @Test void blank_sku_rejected_before_any_read() {
        ProductDetailRuntimeComposer c = composer(neverCatalog, neverBase,
                neverServiceability, neverInventory, neverMedia, MediaUrlResolver.of(BASE_URL));
        assertThrows(IllegalArgumentException.class, () -> c.composeDetail(" ", AT_PIN));
        assertThrows(IllegalArgumentException.class, () -> c.composeDetail(null, AT_PIN));
        assertThrows(NullPointerException.class, () -> c.composeDetail("TZP-1", null));
    }

    // ---- eligibility outcomes gate ALL downstream work --------------------------------

    @Test void not_found_short_circuits_all_commerce_and_media_work() {
        ProductDetailRuntimeComposer c = composer(sku -> CatalogDetailLookup.notFound(),
                neverBase, neverServiceability, neverInventory, neverMedia, MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetailLookup out = c.composeDetail("TZP-GHOST", AT_PIN);
        assertEquals(RuntimeProductDetailLookup.Status.NOT_FOUND, out.status());
        assertNull(out.detail());
    }

    @Test void ineligible_short_circuits_all_commerce_and_media_work() {
        ProductDetailRuntimeComposer c = composer(sku -> CatalogDetailLookup.ineligible(),
                neverBase, neverServiceability, neverInventory, neverMedia, MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetailLookup out = c.composeDetail("TZP-DRAFT", AT_PIN);
        assertEquals(RuntimeProductDetailLookup.Status.INELIGIBLE, out.status());
        assertNull(out.detail());
    }

    // ---- location modes (PR-08 rules, unforked) ----------------------------------------

    @Test void anonymous_detail_calls_no_location_ports_but_composes_catalog_and_media() {
        MediaSet set = new MediaSet(MediaOwnerType.SKU, "TZP-1", 1, true,
                List.of(asset("a1", "p/x/primary.webp", ImageRole.PRIMARY, 0)));
        ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())),
                baseRow(pricedBase()), neverServiceability, neverInventory,
                skuMedia(set), MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetail d = c.composeDetail("TZP-1", LocationQuery.anonymous()).detail();
        assertEquals(StockState.UNKNOWN, d.card().stockState());
        assertNull(d.card().serviceable());
        assertFalse(d.card().buyable());
        assertEquals(26500L, d.card().sellingPricePaise(), "catalog/price facts still visible");
        assertEquals(BASE_URL + "/p/x/primary.webp", d.primaryImageUrl(), "media is location-independent");
    }

    @Test void serviceable_in_stock_detail_is_buyable() {
        RuntimeProductDetail d = happyComposer(null).composeDetail("TZP-1", AT_PIN).detail();
        assertEquals(StockState.IN_STOCK, d.card().stockState());
        assertEquals(Boolean.TRUE, d.card().serviceable());
        assertTrue(d.card().buyable());
        assertEquals(3500L, d.card().discountAmountPaise(), "PR-08 discount rules, not a PDP fork");
        assertEquals(11, d.card().discountPercent());
    }

    @Test void unserviceable_pin_never_queries_inventory() {
        ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())), baseRow(pricedBase()),
                pin -> ServiceabilityResolution.unserviceable(), neverInventory,
                skuMedia(null), MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetail d = c.composeDetail("TZP-1", AT_PIN).detail();
        assertEquals(Boolean.FALSE, d.card().serviceable());
        assertEquals(StockState.UNKNOWN, d.card().stockState());
        assertFalse(d.card().buyable());
    }

    @Test void inactive_and_no_route_areas_degrade_without_inventory() {
        for (ServiceabilityResolution r : List.of(
                ServiceabilityResolution.inactive("SA-9"),
                ServiceabilityResolution.noActiveRoute("SA-9"))) {
            ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())),
                    baseRow(pricedBase()), pin -> r, neverInventory,
                    skuMedia(null), MediaUrlResolver.of(BASE_URL));
            RuntimeProductDetail d = c.composeDetail("TZP-1", AT_PIN).detail();
            assertFalse(d.card().buyable());
            assertEquals(StockState.UNKNOWN, d.card().stockState());
        }
    }

    // ---- inventory states ---------------------------------------------------------------

    @Test void inventory_missing_and_inactive_are_unknown_never_out_of_stock() {
        for (InventoryReadPort port : List.of(
                (InventoryReadPort) (sku, loc) -> InventoryLookup.missing(),
                stock(50, 0, 3, 10, false))) {
            ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())),
                    baseRow(pricedBase()), serviceableAt, port, skuMedia(null), MediaUrlResolver.of(BASE_URL));
            RuntimeProductDetail d = c.composeDetail("TZP-1", AT_PIN).detail();
            assertEquals(StockState.UNKNOWN, d.card().stockState());
            assertFalse(d.card().buyable());
        }
    }

    @Test void out_of_stock_truthful_and_not_buyable() {
        ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())), baseRow(pricedBase()),
                serviceableAt, stock(5, 5, 3, 10, true), skuMedia(null), MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetail d = c.composeDetail("TZP-1", AT_PIN).detail();
        assertEquals(StockState.OUT_OF_STOCK, d.card().stockState());
        assertFalse(d.card().buyable());
    }

    // ---- projection freshness gate: missing base row ------------------------------------

    @Test void missing_base_row_is_degraded_not_not_found() {
        // Catalog says the product EXISTS and is eligible; the projection row is absent. The
        // hard gate: this must NOT become NOT_FOUND, must NOT rebuild, must NOT fabricate price.
        ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())), baseRow(null),
                serviceableAt, stock(50, 0, 3, 10, true), skuMedia(null), MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetailLookup out = c.composeDetail("TZP-1", AT_PIN);
        assertTrue(out.isFound(), "projection absence is degradation, never existence-denial");
        RuntimeProductCard card = out.detail().card();
        assertNull(card.sellingPricePaise(), "price NOT fabricated from a fresh read");
        assertEquals(StockState.IN_STOCK, card.stockState(), "stock truth still flows via PR-08 path");
        assertFalse(card.buyable(), "fails closed without an ACTIVE price");
        assertEquals("Detail T", card.title(), "identity from Catalog facts");
    }

    // ---- media gallery -------------------------------------------------------------------

    @Test void gallery_resolves_ordered_with_primary_first() {
        MediaSet set = new MediaSet(MediaOwnerType.SKU, "TZP-1", 1, true, List.of(
                asset("a2", "p/x/side.webp", ImageRole.GALLERY, 2),
                asset("a1", "p/x/front.webp", ImageRole.PRIMARY, 0),
                asset("a3", "p/x/back.webp", ImageRole.GALLERY, 5)));
        RuntimeProductDetail d = happyComposer(set).composeDetail("TZP-1", AT_PIN).detail();
        assertEquals(List.of(BASE_URL + "/p/x/front.webp", BASE_URL + "/p/x/side.webp",
                        BASE_URL + "/p/x/back.webp"),
                d.gallery().stream().map(RuntimeProductImage::url).toList(),
                "deterministic ascending sortOrder, PRIMARY first");
        assertEquals(BASE_URL + "/p/x/front.webp", d.primaryImageUrl());
        assertEquals(ImageRole.PRIMARY, d.gallery().get(0).role());
        assertEquals("alt a1", d.gallery().get(0).altText());
    }

    @Test void no_media_set_yields_empty_gallery_and_null_primary() {
        RuntimeProductDetail d = happyComposer(null).composeDetail("TZP-1", AT_PIN).detail();
        assertTrue(d.gallery().isEmpty());
        assertNull(d.primaryImageUrl());
        assertTrue(d.card().buyable(), "missing imagery never corrupts commerce facts");
    }

    @Test void gallery_without_primary_role_has_null_primary_url() {
        MediaSet set = new MediaSet(MediaOwnerType.SKU, "TZP-1", 1, true,
                List.of(asset("a1", "p/x/g1.webp", ImageRole.GALLERY, 1)));
        RuntimeProductDetail d = happyComposer(set).composeDetail("TZP-1", AT_PIN).detail();
        assertNull(d.primaryImageUrl(), "no PRIMARY role -> nothing promoted by guesswork");
        assertEquals(1, d.gallery().size());
    }

    @Test void sku_inactive_media_suppresses_even_when_product_media_exists() {
        MediaSet productSet = new MediaSet(MediaOwnerType.PRODUCT, "TZP-1", 1, true,
                List.of(asset("a1", "p/x/prod.webp", ImageRole.PRIMARY, 0)));
        MediaReadPort suppressedSku = (type, id) -> type == MediaOwnerType.SKU
                ? MediaLookup.of(MediaLookup.Status.INACTIVE,
                        new MediaSet(MediaOwnerType.SKU, "TZP-1", 1, false, List.of()))
                : MediaLookup.of(MediaLookup.Status.PRESENT, productSet);
        ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())), baseRow(pricedBase()),
                serviceableAt, stock(5, 0, 1, 10, true), suppressedSku, MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetail d = c.composeDetail("TZP-1", AT_PIN).detail();
        assertTrue(d.gallery().isEmpty(), "operator suppression is never undone by fallback");
    }

    @Test void sku_missing_media_falls_back_to_product_media() {
        MediaSet productSet = new MediaSet(MediaOwnerType.PRODUCT, "TZP-1", 1, true,
                List.of(asset("a1", "p/x/prod.webp", ImageRole.PRIMARY, 0)));
        MediaReadPort fallback = (type, id) -> type == MediaOwnerType.PRODUCT
                ? MediaLookup.of(MediaLookup.Status.PRESENT, productSet) : MediaLookup.missing();
        ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())), baseRow(pricedBase()),
                serviceableAt, stock(5, 0, 1, 10, true), fallback, MediaUrlResolver.of(BASE_URL));
        RuntimeProductDetail d = c.composeDetail("TZP-1", AT_PIN).detail();
        assertEquals(BASE_URL + "/p/x/prod.webp", d.primaryImageUrl());
    }

    @Test void unconfigured_resolver_degrades_imagery_only() {
        MediaSet set = new MediaSet(MediaOwnerType.SKU, "TZP-1", 1, true,
                List.of(asset("a1", "p/x/front.webp", ImageRole.PRIMARY, 0)));
        ProductDetailRuntimeComposer c = composer(foundCatalog(facts(List.of())), baseRow(pricedBase()),
                serviceableAt, stock(5, 0, 1, 10, true), skuMedia(set), MediaUrlResolver.unconfigured());
        RuntimeProductDetail d = c.composeDetail("TZP-1", AT_PIN).detail();
        assertTrue(d.gallery().isEmpty());
        assertNull(d.primaryImageUrl());
        assertNull(d.card().thumbnailUrl());
        assertTrue(d.card().buyable(), "readiness gap degrades imagery only");
    }

    // ---- attributes ---------------------------------------------------------------------

    @Test void attributes_pass_through_in_policy_order_immutable() {
        List<ConsumerAttributeResponse> attrs = List.of(
                new ConsumerAttributeResponse("pack_size", "Pack size", 500, "g"),
                new ConsumerAttributeResponse("origin", "Origin", "India", null));
        RuntimeProductDetail d = composer(foundCatalog(facts(attrs)), baseRow(pricedBase()),
                serviceableAt, stock(5, 0, 1, 10, true), skuMedia(null), MediaUrlResolver.of(BASE_URL))
                .composeDetail("TZP-1", AT_PIN).detail();
        assertEquals(attrs, d.attributes(), "policy order preserved exactly");
        assertThrows(UnsupportedOperationException.class,
                () -> d.attributes().add(new ConsumerAttributeResponse("x", "X", 1, null)));
    }

    @Test void empty_attributes_stay_empty_never_defaulted() {
        RuntimeProductDetail d = happyComposer(null).composeDetail("TZP-1", AT_PIN).detail();
        assertTrue(d.attributes().isEmpty());
    }

    // ---- infrastructure failure propagation ----------------------------------------------

    @Test void catalog_serviceability_inventory_media_failures_propagate_the_exact_instance_untouched() {
        MediaUrlResolver resolver = MediaUrlResolver.of(BASE_URL);
        // assert on the EXACT thrown instance — the contract is "propagated typed and untouched",
        // so a future wrapping edit (new IllegalStateException(..., e)) must fail these tests.
        RuntimeException catalogOutage = new RuntimeException("catalog outage");
        assertSame(catalogOutage, assertThrows(RuntimeException.class, () -> composer(
                sku -> { throw catalogOutage; },
                neverBase, neverServiceability, neverInventory, neverMedia, resolver)
                .composeDetail("TZP-1", AT_PIN)));

        RuntimeException svcOutage = new RuntimeException("serviceability outage");
        assertSame(svcOutage, assertThrows(RuntimeException.class, () -> composer(foundCatalog(facts(List.of())),
                baseRow(pricedBase()), pin -> { throw svcOutage; },
                neverInventory, neverMedia, resolver).composeDetail("TZP-1", AT_PIN)));

        RuntimeException invOutage = new RuntimeException("inventory outage");
        assertSame(invOutage, assertThrows(RuntimeException.class, () -> composer(foundCatalog(facts(List.of())),
                baseRow(pricedBase()), serviceableAt, (sku, loc) -> { throw invOutage; },
                neverMedia, resolver).composeDetail("TZP-1", AT_PIN)));

        InvalidMediaException mediaCorrupt = new InvalidMediaException("corrupt persisted media");
        assertSame(mediaCorrupt, assertThrows(InvalidMediaException.class, () -> composer(foundCatalog(facts(List.of())),
                baseRow(pricedBase()), serviceableAt, stock(5, 0, 1, 10, true),
                (type, id) -> { throw mediaCorrupt; }, resolver).composeDetail("TZP-1", AT_PIN)));
    }

    // ---- authoritative data is NEVER truncated to fit the card projection (PR-09 review, HIGH-2) ----

    private ProductDetailRuntimeComposer bounded(CatalogProductDetailFacts f) {
        return composer(foundCatalog(f), baseRow(null), serviceableAt,
                stock(50, 0, 3, 10, true), skuMedia(null), MediaUrlResolver.of(BASE_URL));
    }

    @Test void over_bound_title_fails_typed_never_truncated() {
        // The Catalog write path is unbounded; ProductCardBaseProjection caps title at MAX_TITLE.
        // A 501-char title can never get a persisted row, so it always hits the degraded path —
        // which must FAIL TYPED, never return an altered (truncated) authoritative title.
        String longTitle = "T".repeat(ProductCardBaseProjection.MAX_TITLE + 1);
        ProductDetailCompositionException ex = assertThrows(ProductDetailCompositionException.class,
                () -> bounded(factsV(longTitle, 3L)).composeDetail("TZP-1", AT_PIN));
        assertTrue(ex.getMessage().contains("TZP-1"));
    }

    @Test void over_bound_brand_code_fails_typed() {
        CatalogProductDetailFacts f = new CatalogProductDetailFacts("TZP-1", "TZP-1", "Detail T",
                "B".repeat(ProductCardBaseProjection.MAX_ID + 1), "TZV-1", 3L, List.of());
        assertThrows(ProductDetailCompositionException.class,
                () -> bounded(f).composeDetail("TZP-1", AT_PIN));
    }

    @Test void over_bound_vertical_id_fails_typed() {
        CatalogProductDetailFacts f = new CatalogProductDetailFacts("TZP-1", "TZP-1", "Detail T",
                "BR", "V".repeat(ProductCardBaseProjection.MAX_ID + 1), 3L, List.of());
        assertThrows(ProductDetailCompositionException.class,
                () -> bounded(f).composeDetail("TZP-1", AT_PIN));
    }

    @Test void title_at_exact_limit_degrades_found_and_is_preserved_verbatim() {
        String exact = "T".repeat(ProductCardBaseProjection.MAX_TITLE);
        RuntimeProductDetailLookup out = bounded(factsV(exact, 3L)).composeDetail("TZP-1", AT_PIN);
        assertTrue(out.isFound());
        assertEquals(exact, out.detail().card().title(), "exact-limit title preserved verbatim, not altered");
        assertFalse(out.detail().card().buyable(), "still fails closed — no fabricated price");
    }

    // ---- base/catalog-version freshness gate (PR-09 review, HIGH-3) ----

    @Test void base_version_equal_to_facts_uses_the_base_snapshot() {
        RuntimeProductDetail d = composer(foundCatalog(facts(List.of())), baseRow(baseAt("Detail T", 3L)),
                serviceableAt, stock(50, 0, 3, 10, true), skuMedia(null), MediaUrlResolver.of(BASE_URL))
                .composeDetail("TZP-1", AT_PIN).detail();
        assertEquals(26500L, d.card().sellingPricePaise(), "catalog-fresh base snapshot is used");
        assertTrue(d.card().buyable());
    }

    @Test void stale_base_version_is_not_served_fresh_identity_fail_closed() {
        // base v9 carries an OLD title + a price; facts are v10 with a NEW title. The stale base
        // must NOT be served as authoritative — fresh Catalog identity, commerce facts fail closed.
        RuntimeProductDetail d = composer(foundCatalog(factsV("New Title", 10L)),
                baseRow(baseAt("OLD Title", 9L)), serviceableAt, stock(50, 0, 3, 10, true),
                skuMedia(null), MediaUrlResolver.of(BASE_URL)).composeDetail("TZP-1", AT_PIN).detail();
        assertEquals("New Title", d.card().title(), "fresh Catalog title, never the stale base title");
        assertNull(d.card().sellingPricePaise(), "stale base commerce facts are not served");
        assertFalse(d.card().buyable(), "fails closed under uncertain projection freshness");
    }

    @Test void base_version_ahead_of_catalog_read_is_typed_inconsistency() {
        // base built from a NEWER catalog version than our read returned: impossible/suspicious.
        assertThrows(ProductDetailCompositionException.class, () -> composer(foundCatalog(factsV("Detail T", 9L)),
                baseRow(baseAt("Detail T", 10L)), serviceableAt, stock(50, 0, 3, 10, true),
                skuMedia(null), MediaUrlResolver.of(BASE_URL)).composeDetail("TZP-1", AT_PIN));
    }

    // ---- leak guards + no-fabrication (STEP 34) -------------------------------------------

    @Test void BLOCKER_detail_models_carry_no_internal_location_or_counter_fields() {
        List<String> forbidden = List.of("fulfillment", "onhand", "on_hand", "reserved",
                "inventoryversion", "assetkey", "warehouse", "storeid", "darkstore", "pincode",
                "supplier", "procurement", "purchasecost", "auditid", "eventid");
        for (Class<?> c : List.of(RuntimeProductDetail.class, RuntimeProductImage.class,
                RuntimeProductDetailLookup.class, CatalogProductDetailFacts.class,
                CatalogDetailLookup.class)) {
            for (RecordComponent rc : c.getRecordComponents()) {
                String name = rc.getName().toLowerCase(Locale.ROOT);
                for (String bad : forbidden) {
                    assertFalse(name.contains(bad),
                            c.getSimpleName() + "." + rc.getName() + " leaks internal state");
                }
            }
        }
    }

    @Test void unsupported_fields_are_absent_not_defaulted() {
        // No authoritative source exists for these — the MODEL must not even have the slots
        // (fake zero/star values are not the same as source-backed values).
        List<String> unsourced = List.of("rating", "ratingcount", "seller", "badge",
                "offersummary", "sponsored", "description", "brandname", "packsize");
        for (Class<?> c : List.of(RuntimeProductDetail.class, RuntimeProductImage.class,
                CatalogProductDetailFacts.class)) {
            for (RecordComponent rc : c.getRecordComponents()) {
                String name = rc.getName().toLowerCase(Locale.ROOT);
                for (String bad : unsourced) {
                    assertFalse(name.contains(bad),
                            c.getSimpleName() + "." + rc.getName() + " fabricates an unsourced field");
                }
            }
        }
        // ETA: carried by the card as nullable per the frozen contract — and always null.
        RuntimeProductDetail d = happyComposer(null).composeDetail("TZP-1", AT_PIN).detail();
        assertNull(d.card().etaMinutesMin());
        assertNull(d.card().etaMinutesMax());
    }

    // ---- outcome/value shape invariants ----------------------------------------------------

    @Test void lookup_shape_invariants_enforced() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeProductDetailLookup(RuntimeProductDetailLookup.Status.FOUND, null));
        RuntimeProductDetail d = happyComposer(null).composeDetail("TZP-1", AT_PIN).detail();
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeProductDetailLookup(RuntimeProductDetailLookup.Status.NOT_FOUND, d));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogDetailLookup(CatalogDetailLookup.Status.FOUND, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogDetailLookup(CatalogDetailLookup.Status.INELIGIBLE, facts(List.of())));
    }

    @Test void image_and_detail_invariants_enforced() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProductImage(
                " ", ImageRole.GALLERY, 1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProductImage(
                "https://m/x.webp", ImageRole.PRIMARY, 3, null, null, null)); // primary must sort 0
        RuntimeProductCard card = happyComposer(null).composeDetail("TZP-1", AT_PIN).detail().card();
        RuntimeProductImage g1 = new RuntimeProductImage("https://m/a.webp", ImageRole.GALLERY, 1, null, null, null);
        RuntimeProductImage g1dup = new RuntimeProductImage("https://m/a.webp", ImageRole.GALLERY, 2, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProductDetail(
                card, List.of(), null, List.of(g1, g1dup))); // duplicate url
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProductDetail(
                card, List.of(), "https://m/ghost.webp", List.of(g1))); // primary not in gallery
        RuntimeProductImage outOfOrder = new RuntimeProductImage("https://m/b.webp", ImageRole.GALLERY, 1, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProductDetail(
                card, List.of(), null, List.of(g1, outOfOrder))); // non-ascending order
        assertThrows(UnsupportedOperationException.class,
                () -> new RuntimeProductDetail(card, List.of(), null, List.of(g1)).gallery().clear());
    }
}
