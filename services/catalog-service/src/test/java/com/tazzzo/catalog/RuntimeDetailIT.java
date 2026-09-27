package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.consumer.ConsumerFailures;
import com.tazzzo.catalog.consumer.ConsumerProductResolver;
import com.tazzzo.catalog.consumer.ConsumerProjectionService;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.commerce.contract.LocationQuery;
import com.tazzzo.commerce.contract.Pincode;
import com.tazzzo.commerce.contract.StockState;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.CatalogProductDetailReader;
import com.tazzzo.commerce.read.ProductCardBaseReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.commerce.read.ProductCardRuntimeEnricher;
import com.tazzzo.commerce.read.ProductDetailRuntimeComposer;
import com.tazzzo.commerce.read.RuntimeProductDetail;
import com.tazzzo.commerce.read.RuntimeProductDetailLookup;
import com.tazzzo.commerce.read.RuntimeProductImage;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.money.Currency;
import com.tazzzo.inventory.InventoryLookup;
import com.tazzzo.inventory.InventoryReadPort;
import com.tazzzo.inventory.InventoryService;
import com.tazzzo.inventory.SetInventoryCommand;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaLookup;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.MediaReadPort;
import com.tazzzo.media.MediaService;
import com.tazzzo.media.MediaUrlResolver;
import com.tazzzo.media.UpsertMediaSetCommand;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import com.tazzzo.serviceability.ServiceabilityReadPort;
import com.tazzzo.serviceability.ServiceabilityRoute;
import com.tazzzo.serviceability.ServiceabilityService;
import com.tazzzo.serviceability.UpsertServiceAreaCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-09 runtime product detail — Testcontainers integration with REAL Catalog, consumer
 * projection, Pricing, Inventory, Media, Serviceability and the card projection stack.
 * Cross-location isolation and the projection-freshness hard gate are the primary boundaries.
 */
class RuntimeDetailIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String MEDIA_BASE = "https://media.test.example";

    private PricingService pricing() {
        return new PricingService(new Tx(client), new WritePath(db), CLOCK);
    }

    private InventoryService inventoryService() {
        return new InventoryService(new Tx(client), new WritePath(db), CLOCK);
    }

    private ServiceabilityService serviceabilityService() {
        return new ServiceabilityService(new Tx(client), db, new DomainAudit(db, CLOCK), CLOCK);
    }

    private MediaService mediaService() {
        return new MediaService(new Tx(client), new WritePath(db), CLOCK);
    }

    private ProductCardProjectionService projector() {
        return new ProductCardProjectionService(new CatalogCardReader(db), pricing(),
                mediaService(), db, CLOCK);
    }

    private ProductDetailRuntimeComposer composer() {
        return composer(serviceabilityService(), inventoryService(), mediaService());
    }

    private ProductDetailRuntimeComposer composer(ServiceabilityReadPort svc,
                                                  InventoryReadPort inv, MediaReadPort media) {
        MediaUrlResolver resolver = MediaUrlResolver.of(MEDIA_BASE);
        return new ProductDetailRuntimeComposer(
                new CatalogProductDetailReader(
                        new ConsumerProductResolver(db), new ConsumerProjectionService(db)),
                new ProductCardBaseReader(db),
                new ProductCardRuntimeEnricher(svc, inv, resolver),
                media, resolver);
    }

    // ---------- fixtures ----------

    private void seedProduct(String sku, String lifecycle, Map<String, Object> attributes) {
        db.getCollection("products").insertOne(new Document("_id", sku)
                .append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", sku))
                .append("brand_code", "BR").append("title", "T " + sku)
                .append("lifecycle", lifecycle)
                .append("classification", new Document("vertical_id", "TZV-000037")
                        .append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document(attributes))
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", 1).append("created_at", java.util.Date.from(NOW)));
    }

    /** Eligible product + canonical ACTIVE price + built base projection row. */
    private void seedDetail(String sku, long sellingPaise) {
        seedProduct(sku, "active", Map.of());
        pricing().upsertPrice(new UpsertPriceCommand(sku, sellingPaise, sellingPaise + 5000,
                Currency.INR, null, null, "seed", null));
        projector().rebuildOne(sku);
    }

    private void route(String pin, String area, String location) {
        serviceabilityService().upsertServiceArea(new UpsertServiceAreaCommand(
                pin, area, List.of(new ServiceabilityRoute(location, 0, true)), "seed", null));
    }

    private void stockAt(String sku, String location, long onHand) {
        inventoryService().setInventory(new SetInventoryCommand(sku, location, onHand, 2, 10, "seed", null));
    }

    private LocationQuery at(String pin) {
        return LocationQuery.ofPin(new Pincode(pin));
    }

    private RuntimeProductDetail found(String sku, LocationQuery location) {
        RuntimeProductDetailLookup out = composer().composeDetail(sku, location);
        assertTrue(out.isFound(), sku + " expected FOUND, was " + out.status());
        return out.detail();
    }

    // ---------- A/B/C/D: location matrix on ONE SKU ----------

    @Test void BLOCKER_same_sku_across_anonymous_and_two_pins_never_contaminates() {
        seedDetail("TZP-D1", 10000L);
        route("560001", "SA-A", "FL-A");
        route("560002", "SA-B", "FL-B");
        stockAt("TZP-D1", "FL-A", 10);
        stockAt("TZP-D1", "FL-B", 0);

        // A. anonymous: catalog facts visible, stock UNKNOWN, not buyable
        RuntimeProductDetail anon = found("TZP-D1", LocationQuery.anonymous());
        assertEquals("T TZP-D1", anon.card().title());
        assertEquals(10000L, anon.card().sellingPricePaise());
        assertEquals(StockState.UNKNOWN, anon.card().stockState());
        assertNull(anon.card().serviceable());
        assertFalse(anon.card().buyable());

        // B. PIN A with stock: buyable
        RuntimeProductDetail atA = found("TZP-D1", at("560001"));
        assertEquals(StockState.IN_STOCK, atA.card().stockState());
        assertTrue(atA.card().buyable());

        // C. PIN B with zero stock: truthful OUT_OF_STOCK, not buyable
        RuntimeProductDetail atB = found("TZP-D1", at("560002"));
        assertEquals(StockState.OUT_OF_STOCK, atB.card().stockState());
        assertFalse(atB.card().buyable());

        // D. PIN A again: original state restored — zero cross-request contamination
        RuntimeProductDetail atAAgain = found("TZP-D1", at("560001"));
        assertEquals(StockState.IN_STOCK, atAAgain.card().stockState());
        assertTrue(atAAgain.card().buyable());
    }

    // ---------- E: shared area, different fulfillment ----------

    @Test void BLOCKER_shared_service_area_does_not_imply_shared_fulfillment() {
        seedDetail("TZP-D2", 10000L);
        route("560011", "SA-SHARED", "FL-P");
        route("560012", "SA-SHARED", "FL-Q");
        stockAt("TZP-D2", "FL-P", 10);
        stockAt("TZP-D2", "FL-Q", 0);

        assertTrue(found("TZP-D2", at("560011")).card().buyable());
        assertEquals(StockState.OUT_OF_STOCK, found("TZP-D2", at("560012")).card().stockState());
    }

    // ---------- F: composition performs zero writes ----------

    @Test void detail_composition_writes_nothing_anywhere() {
        seedDetail("TZP-D3", 10000L);
        route("560021", "SA-W", "FL-W");
        stockAt("TZP-D3", "FL-W", 5);
        mediaService().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.SKU, "TZP-D3",
                List.of(new MediaAsset("a1", "d3/front.webp", ImageRole.PRIMARY, 0, "front", 800, 600, "image/webp")),
                "seed", null));

        List<String> collections = List.of("products", "product_card_base", "price_current",
                "inventory", "media_refs", "service_areas", "domain_events");
        List<List<Document>> before = snapshot(collections);
        found("TZP-D3", at("560021"));
        found("TZP-D3", LocationQuery.anonymous());
        assertEquals(before, snapshot(collections), "PDP composition is ONE-WAY: zero writes");
    }

    private List<List<Document>> snapshot(List<String> collections) {
        List<List<Document>> all = new ArrayList<>();
        for (String c : collections) {
            all.add(db.getCollection(c).find().into(new ArrayList<>()));
        }
        return all;
    }

    // ---------- G/H/I: call-count discipline ----------

    private static final class CountingInventory implements InventoryReadPort {
        private final InventoryReadPort delegate;
        final AtomicInteger reads = new AtomicInteger();
        CountingInventory(InventoryReadPort delegate) { this.delegate = delegate; }
        @Override public InventoryLookup findInventory(String sku, String loc) {
            reads.incrementAndGet();
            return delegate.findInventory(sku, loc);
        }
        @Override public Map<String, InventoryLookup> findInventoryBatch(Collection<String> ids, String loc) {
            reads.incrementAndGet();
            return delegate.findInventoryBatch(ids, loc);
        }
    }

    private static final class CountingMedia implements MediaReadPort {
        private final MediaReadPort delegate;
        final AtomicInteger reads = new AtomicInteger();
        CountingMedia(MediaReadPort delegate) { this.delegate = delegate; }
        @Override public MediaLookup findMedia(MediaOwnerType type, String id) {
            reads.incrementAndGet();
            return delegate.findMedia(type, id);
        }
    }

    @Test void unserviceable_and_anonymous_make_zero_inventory_calls() {
        seedDetail("TZP-D4", 10000L);
        route("560031", "SA-X", "FL-X"); // configured, but we ask from an uncovered PIN

        AtomicInteger svcCalls = new AtomicInteger();
        ServiceabilityService realSvc = serviceabilityService();
        ServiceabilityReadPort countingSvc = pin -> {
            svcCalls.incrementAndGet();
            return realSvc.resolveByPincode(pin);
        };
        CountingInventory inv = new CountingInventory(inventoryService());

        // G. valid but uncovered PIN: one serviceability resolution, zero inventory work
        composer(countingSvc, inv, mediaService()).composeDetail("TZP-D4", at("999999"));
        assertEquals(1, svcCalls.get());
        assertEquals(0, inv.reads.get(), "unserviceable never reads inventory");

        // H. anonymous: zero serviceability AND zero inventory
        svcCalls.set(0);
        composer(countingSvc, inv, mediaService()).composeDetail("TZP-D4", LocationQuery.anonymous());
        assertEquals(0, svcCalls.get());
        assertEquals(0, inv.reads.get());
    }

    @Test void ineligible_product_rejects_before_any_commerce_or_media_work() {
        seedProduct("TZP-DRAFT9", "draft", Map.of());
        AtomicInteger svcCalls = new AtomicInteger();
        ServiceabilityReadPort countingSvc = pin -> {
            svcCalls.incrementAndGet();
            throw new AssertionError("unreachable");
        };
        CountingInventory inv = new CountingInventory(inventoryService());
        CountingMedia media = new CountingMedia(mediaService());

        RuntimeProductDetailLookup out =
                composer(countingSvc, inv, media).composeDetail("TZP-DRAFT9", at("560031"));
        assertEquals(RuntimeProductDetailLookup.Status.INELIGIBLE, out.status());
        assertEquals(0, svcCalls.get(), "eligibility rejection precedes serviceability");
        assertEquals(0, inv.reads.get(), "eligibility rejection precedes inventory");
        assertEquals(0, media.reads.get(), "eligibility rejection precedes media");
    }

    @Test void serviceable_detail_uses_one_inventory_read_and_bounded_media_reads() {
        seedDetail("TZP-D5", 10000L);
        route("560041", "SA-Y", "FL-Y");
        stockAt("TZP-D5", "FL-Y", 5);
        CountingInventory inv = new CountingInventory(inventoryService());
        CountingMedia media = new CountingMedia(mediaService());
        composer(serviceabilityService(), inv, media).composeDetail("TZP-D5", at("560041"));
        assertEquals(1, inv.reads.get(), "one SKU, one inventory read");
        assertEquals(2, media.reads.get(), "SKU miss + PRODUCT fallback = the bounded worst case");
    }

    // ---------- reader outcomes ----------

    @Test void absent_and_draft_products_yield_typed_outcomes() {
        assertEquals(RuntimeProductDetailLookup.Status.NOT_FOUND,
                composer().composeDetail("TZP-NEVER", at("560001")).status());

        seedProduct("TZP-DRAFT1", "draft", Map.of());
        assertEquals(RuntimeProductDetailLookup.Status.INELIGIBLE,
                composer().composeDetail("TZP-DRAFT1", at("560001")).status());
    }

    // ---------- merge-chain resolution: SAME shared resolver as the legacy consumer PDP ----------

    /** Insert an eligible product then mark it merged into {@code target} (direct write). */
    private void mergedTo(String id, Object target) {
        seedProduct(id, "active", Map.of());
        Document set = new Document("lifecycle", "merged");
        if (target != null) {
            set.append("merged_into", target);
        }
        db.getCollection("products").updateOne(Filters.eq("_id", id), new Document("$set", set));
    }

    @Test void A_active_product_resolves_to_itself() {
        seedDetail("TZP-MA", 10000L);
        RuntimeProductDetail d = found("TZP-MA", LocationQuery.anonymous());
        assertEquals("TZP-MA", d.card().skuId());
        assertEquals("TZP-MA", d.card().productId());
    }

    @Test void B_merged_loser_resolves_to_survivor_identity_never_the_loser() {
        seedDetail("TZP-MB-SURV", 10000L);          // survivor B, eligible, with base row
        mergedTo("TZP-MB-LOSER", "TZP-MB-SURV");      // loser A -> B
        RuntimeProductDetail d = found("TZP-MB-LOSER", LocationQuery.anonymous());
        assertEquals("TZP-MB-SURV", d.card().skuId(), "survivor identity, never the requested loser");
        assertEquals("TZP-MB-SURV", d.card().productId());
        assertEquals("T TZP-MB-SURV", d.card().title());
    }

    @Test void C_two_hop_chain_resolves_to_final_survivor() {
        seedDetail("TZP-MC-C", 10000L);               // final survivor
        mergedTo("TZP-MC-B", "TZP-MC-C");
        mergedTo("TZP-MC-A", "TZP-MC-B");
        assertEquals("TZP-MC-C", found("TZP-MC-A", LocationQuery.anonymous()).card().skuId());
    }

    @Test void D_merge_cycle_is_typed_corruption_not_a_business_absence() {
        mergedTo("TZP-CYC1", "TZP-CYC2");
        mergedTo("TZP-CYC2", "TZP-CYC1");
        assertThrows(ConsumerFailures.Unavailable.class,
                () -> composer().composeDetail("TZP-CYC1", at("560001")));
    }

    @Test void E_missing_merge_target_is_typed_corruption() {
        mergedTo("TZP-MISS", "TZP-GONE");
        assertThrows(ConsumerFailures.Unavailable.class,
                () -> composer().composeDetail("TZP-MISS", at("560001")));
    }

    @Test void F_blank_merge_pointer_is_typed_corruption() {
        mergedTo("TZP-NULLPTR", null);
        assertThrows(ConsumerFailures.Unavailable.class,
                () -> composer().composeDetail("TZP-NULLPTR", at("560001")));
    }

    @Test void G_chain_beyond_max_hops_is_typed_corruption() {
        // 33 merged rows H00->H01->...->H32 (all merged): the 33rd hop exceeds MAX_MERGE_HOPS=32.
        for (int i = 0; i <= ConsumerProductResolver.MAX_MERGE_HOPS; i++) {
            mergedTo(String.format("TZP-H%02d", i), String.format("TZP-H%02d", i + 1));
        }
        assertThrows(ConsumerFailures.Unavailable.class,
                () -> composer().composeDetail("TZP-H00", at("560001")));
    }

    @Test void H_survivor_that_is_ineligible_is_ineligible() {
        seedProduct("TZP-MH-SURV", "discontinued", Map.of()); // survivor exists but not eligible
        mergedTo("TZP-MH-LOSER", "TZP-MH-SURV");
        assertEquals(RuntimeProductDetailLookup.Status.INELIGIBLE,
                composer().composeDetail("TZP-MH-LOSER", at("560001")).status());
    }

    // ---------- base/catalog-version freshness gate ----------

    @Test void version_A_matched_base_composes_normally() {
        seedDetail("TZP-VA", 10000L);
        route("560071", "SA-VA", "FL-VA");
        stockAt("TZP-VA", "FL-VA", 5);
        RuntimeProductDetail d = found("TZP-VA", at("560071"));
        assertEquals(10000L, d.card().sellingPricePaise());
        assertTrue(d.card().buyable());
    }

    @Test void version_B_stale_base_is_not_served_fresh_identity_and_no_rebuild() {
        seedDetail("TZP-VB", 10000L);                 // base at product version 1
        long projVersionBefore = db.getCollection("product_card_base")
                .find(Filters.eq("sku_id", "TZP-VB")).first().get("projection_version", Number.class).longValue();
        // Catalog moves ahead (version 2, new title) WITHOUT a projection rebuild.
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-VB"),
                new Document("$set", new Document("version", 2).append("title", "Renamed VB")));
        route("560072", "SA-VB", "FL-VB");
        stockAt("TZP-VB", "FL-VB", 5);

        RuntimeProductDetail d = found("TZP-VB", at("560072"));
        assertEquals("Renamed VB", d.card().title(), "fresh Catalog title, never the stale base title");
        assertNull(d.card().sellingPricePaise(), "stale base commerce facts not served; fails closed");
        assertFalse(d.card().buyable());
        // E. version mismatch must NOT trigger rebuildOne — the projection stays NOT LIVE.
        long projVersionAfter = db.getCollection("product_card_base")
                .find(Filters.eq("sku_id", "TZP-VB")).first().get("projection_version", Number.class).longValue();
        assertEquals(projVersionBefore, projVersionAfter, "no rebuildOne fan-out on a PDP request");
        long baseCatalogVersion = db.getCollection("product_card_base").find(Filters.eq("sku_id", "TZP-VB"))
                .first().get("source_versions", Document.class).get("catalog_version", Number.class).longValue();
        assertEquals(1L, baseCatalogVersion, "base row untouched at its stale catalog version");
    }

    @Test void version_C_base_ahead_of_catalog_read_is_typed_inconsistency() {
        seedProduct("TZP-VC", "active", Map.of());
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-VC"),
                new Document("$set", new Document("version", 10)));
        pricing().upsertPrice(new UpsertPriceCommand("TZP-VC", 10000L, 15000L,
                Currency.INR, null, null, "seed", null));
        projector().rebuildOne("TZP-VC");             // base built at catalog version 10
        // Now the fresh Catalog read returns an OLDER version than the base captured (inconsistent).
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-VC"),
                new Document("$set", new Document("version", 9)));
        assertThrows(com.tazzzo.commerce.read.ProductDetailCompositionException.class,
                () -> composer().composeDetail("TZP-VC", LocationQuery.anonymous()));
    }

    // ---------- governed attributes end-to-end ----------

    @Test void attributes_flow_through_projection_policy_in_display_order() {
        seedDetail("TZP-D6", 10000L);
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-D6"),
                new Document("$set", new Document("attributes",
                        new Document("aged", true).append("grain_length", "extra-long"))));
        db.getCollection("attribute_definitions").insertOne(
                new Document("key", "aged").append("version", 1).append("type", "boolean")
                        .append("governance", "descriptive").append("status", "active"));
        db.getCollection("attribute_definitions").insertOne(
                new Document("key", "grain_length").append("version", 1).append("type", "enum_open")
                        .append("governance", "descriptive").append("status", "active")
                        .append("known_values", List.of("extra-long")));
        db.getCollection("consumer_projection_policy").insertOne(
                new Document("vertical_id", "TZV-000037").append("projection_version", "v1")
                        .append("attributes", List.of(
                                new Document("attribute_key", "grain_length")
                                        .append("display_order", 1).append("display_label", "Grain length"),
                                new Document("attribute_key", "aged")
                                        .append("display_order", 2).append("display_label", "Aged"))));
        try {
            RuntimeProductDetail d = found("TZP-D6", LocationQuery.anonymous());
            assertEquals(List.of("grain_length", "aged"),
                    d.attributes().stream().map(a -> a.key()).toList(),
                    "policy display order, deterministic");
            assertEquals("Grain length", d.attributes().get(0).label());
        } finally {
            db.getCollection("consumer_projection_policy")
                    .deleteOne(Filters.eq("vertical_id", "TZV-000037"));
        }
    }

    @Test void no_projection_policy_means_empty_attributes_never_raw_map_leak() {
        seedDetail("TZP-D7", 10000L);
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-D7"),
                new Document("$set", new Document("attributes", new Document("internal_note", "x"))));
        RuntimeProductDetail d = found("TZP-D7", LocationQuery.anonymous());
        assertTrue(d.attributes().isEmpty(), "default-deny: no policy -> nothing published");
    }

    // ---------- media gallery end-to-end ----------

    @Test void gallery_is_ordered_resolved_and_suppressible() {
        seedDetail("TZP-D8", 10000L);
        mediaService().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.SKU, "TZP-D8",
                List.of(
                        new MediaAsset("a1", "d8/front.webp", ImageRole.PRIMARY, 0, "front", 800, 600, "image/webp"),
                        new MediaAsset("a2", "d8/side.webp", ImageRole.GALLERY, 1, "side", 800, 600, "image/webp"),
                        new MediaAsset("a3", "d8/back.webp", ImageRole.GALLERY, 2, "back", 800, 600, "image/webp")),
                "seed", null));

        RuntimeProductDetail d = found("TZP-D8", LocationQuery.anonymous());
        assertEquals(List.of(MEDIA_BASE + "/d8/front.webp", MEDIA_BASE + "/d8/side.webp",
                        MEDIA_BASE + "/d8/back.webp"),
                d.gallery().stream().map(RuntimeProductImage::url).toList());
        assertEquals(MEDIA_BASE + "/d8/front.webp", d.primaryImageUrl());
        // card thumbnail (base snapshot) agrees with the fresh primary here — seeded before rebuild?
        // No: media was seeded AFTER rebuildOne, so the SNAPSHOT thumbnail is null while the fresh
        // gallery is served — exactly the documented NOT-LIVE freshness debt, asserted explicitly:
        assertNull(d.card().thumbnailUrl(), "stale base snapshot; PR-10 freshness closes this");

        // operator suppression: switch the SKU set off -> imagery disappears, commerce unaffected
        db.getCollection("media_refs").updateOne(
                Filters.and(Filters.eq("owner_type", "SKU"), Filters.eq("owner_id", "TZP-D8")),
                new Document("$set", new Document("active", false)));
        RuntimeProductDetail suppressed = found("TZP-D8", LocationQuery.anonymous());
        assertTrue(suppressed.gallery().isEmpty());
        assertNull(suppressed.primaryImageUrl());
        assertEquals(10000L, suppressed.card().sellingPricePaise());
    }

    // ---------- projection freshness hard gate ----------

    @Test void eligible_product_without_base_row_is_found_degraded_and_fails_closed() {
        seedProduct("TZP-D9", "active", Map.of());
        pricing().upsertPrice(new UpsertPriceCommand("TZP-D9", 7000L, 9000L,
                Currency.INR, null, null, "seed", null));
        // deliberately NO projector().rebuildOne — the row does not exist
        route("560051", "SA-Z", "FL-Z");
        stockAt("TZP-D9", "FL-Z", 5);

        RuntimeProductDetailLookup out = composer().composeDetail("TZP-D9", at("560051"));
        assertTrue(out.isFound(), "Catalog is the existence authority, not the projection");
        assertNull(out.detail().card().sellingPricePaise(), "no fresh price re-read, no fabrication");
        assertEquals(StockState.IN_STOCK, out.detail().card().stockState());
        assertFalse(out.detail().card().buyable(), "fails closed without the ACTIVE price snapshot");
        assertEquals(0, db.getCollection("product_card_base")
                        .countDocuments(Filters.eq("sku_id", "TZP-D9")),
                "no rebuildOne fan-out was triggered by the request");
    }

    // ---------- J: mixed-location concurrency ----------

    @Test void concurrent_mixed_location_detail_requests_stay_isolated() throws Exception {
        seedDetail("TZP-DC", 10000L);
        route("560061", "SA-CA", "FL-CA");
        route("560062", "SA-CB", "FL-CB");
        stockAt("TZP-DC", "FL-CA", 10);
        stockAt("TZP-DC", "FL-CB", 0);

        ProductDetailRuntimeComposer shared = composer();
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final boolean pinA = i % 2 == 0;
            results.add(pool.submit(() -> {
                start.await();
                RuntimeProductDetail d = shared.composeDetail("TZP-DC",
                        at(pinA ? "560061" : "560062")).detail();
                return pinA
                        ? d.card().buyable() && d.card().stockState() == StockState.IN_STOCK
                        : !d.card().buyable() && d.card().stockState() == StockState.OUT_OF_STOCK;
            }));
        }
        start.countDown();
        for (Future<Boolean> r : results) {
            assertTrue(r.get(), "every request sees exactly its own location's truth");
        }
        pool.shutdownNow();
    }
}
