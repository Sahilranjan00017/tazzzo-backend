package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.commerce.read.CatalogCardReadPort;
import com.tazzzo.commerce.read.CatalogCardReader;
import com.tazzzo.commerce.read.ProductCardProjectionService;
import com.tazzzo.commerce.read.RebuildOutcome;
import com.tazzzo.common.money.Currency;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.MediaService;
import com.tazzzo.media.UpsertMediaSetCommand;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-07 ProductCardBaseProjection — Testcontainers integration. Builds real projections from
 * consumer-eligible Catalog fixtures + canonical Pricing + Media, and proves rebuild semantics,
 * no-op convergence, failure preservation, unpublish removal, concurrency, and the strict
 * no-location persisted boundary.
 */
class ProductCardProjectionIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private PricingService pricing() {
        return new PricingService(new Tx(client), new WritePath(db), CLOCK);
    }

    private MediaService media() {
        return new MediaService(new Tx(client), new WritePath(db), CLOCK);
    }

    private ProductCardProjectionService projector() {
        return new ProductCardProjectionService(new CatalogCardReader(db), pricing(), media(), db, CLOCK);
    }

    /**
     * Insert a consumer-ELIGIBLE, VALIDATOR-CONFORMANT product fixture — same shape as
     * {@code AbstractConsumerIT.product(...)} (identity/attributes/attributes_meta/created_at
     * are required by the products $jsonSchema validator).
     */
    private void seedEligibleProduct(String id, String title, String brand, int version) {
        db.getCollection("products").insertOne(new Document("_id", id)
                .append("product_type", "single")
                .append("identity", new Document("type", "internal").append("internal_key", id))
                .append("brand_code", brand)
                .append("title", title)
                .append("lifecycle", "active")
                .append("classification", new Document("vertical_id", "TZV-000037")
                        .append("release_id", "R1").append("status", "confirmed"))
                .append("attributes", new Document())
                .append("attributes_meta", new Document("validated_release", "R1"))
                .append("version", version)
                .append("created_at", java.util.Date.from(NOW)));
    }

    private Document row(String sku) {
        return db.getCollection("product_card_base").find(Filters.eq("sku_id", sku)).first();
    }

    @Test void builds_full_card_from_catalog_pricing_media() {
        seedEligibleProduct("TZP-PC1", "Aashirvaad Atta 5kg", "AASHIRVAAD", 3);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC1", 26500L, 30000L, Currency.INR,
                null, null, "seed", null));
        media().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.SKU, "TZP-PC1",
                List.of(new MediaAsset("p", "p/TZP-PC1/front.webp", ImageRole.PRIMARY, 0,
                        null, null, null, null)), "seed", null));

        assertEquals(RebuildOutcome.CREATED, projector().rebuildOne("TZP-PC1"));
        Document d = row("TZP-PC1");
        assertEquals("Aashirvaad Atta 5kg", d.getString("title"));
        assertEquals("AASHIRVAAD", d.getString("brand_code"));
        assertEquals("TZV-000037", d.getString("vertical_id"));
        assertEquals("ACTIVE", d.getString("price_status"));
        assertEquals(26500L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals("p/TZP-PC1/front.webp", d.getString("primary_asset_key"));
        Document sv = d.get("source_versions", Document.class);
        assertEquals(3L, ((Number) sv.get("catalog_version")).longValue());
        assertEquals(1L, ((Number) sv.get("price_version")).longValue());
        assertEquals(1L, ((Number) sv.get("media_version")).longValue());
        assertEquals(1L, ((Number) d.get("projection_version")).longValue());
        // BSON int64 money/version
        assertInstanceOf(Long.class, d.get("selling_price_paise"));
        assertInstanceOf(Long.class, d.get("mrp_paise"));
        assertInstanceOf(Long.class, d.get("projection_version"));
    }

    @Test void BLOCKER_persisted_document_contains_no_location_or_stock_keys() {
        seedEligibleProduct("TZP-PC2", "Rice 1kg", "B", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC2", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-PC2");
        List<String> forbidden = List.of("stock", "available", "reserved", "lowstock",
                "serviceable", "servicearea", "fulfillment", "warehouse", "storeid", "darkstore",
                "eta", "deliverypromise", "buyable", "pincode", "onhand", "url");
        assertKeysClean(row("TZP-PC2"), forbidden);
    }

    private void assertKeysClean(Document d, List<String> forbidden) {
        for (Map.Entry<String, Object> e : d.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            for (String bad : forbidden) {
                assertFalse(key.contains(bad), "persisted key '" + e.getKey() + "' violates boundary");
            }
            if (e.getValue() instanceof Document nested) {
                assertKeysClean(nested, forbidden);
            }
        }
    }

    @Test void rebuild_with_unchanged_sources_is_noop_without_version_churn() {
        seedEligibleProduct("TZP-PC3", "Dal", "D", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC3", 100L, 200L, Currency.INR, null, null, "s", null));
        assertEquals(RebuildOutcome.CREATED, projector().rebuildOne("TZP-PC3"));
        assertEquals(RebuildOutcome.NOOP, projector().rebuildOne("TZP-PC3"));
        assertEquals(RebuildOutcome.NOOP, projector().rebuildOne("TZP-PC3"));
        assertEquals(1L, ((Number) row("TZP-PC3").get("projection_version")).longValue());
    }

    @Test void price_change_reflects_on_rebuild() {
        seedEligibleProduct("TZP-PC4", "Oil", "F", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC4", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-PC4");
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC4", 150L, 200L, Currency.INR, null, null, "s", 1L));
        assertEquals(RebuildOutcome.UPDATED, projector().rebuildOne("TZP-PC4"));
        Document d = row("TZP-PC4");
        assertEquals(150L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals(2L, ((Number) d.get("projection_version")).longValue());
        assertEquals(2L, ((Number) d.get("source_versions", Document.class).get("price_version")).longValue());
    }

    @Test void media_and_title_changes_reflect_on_rebuild() {
        seedEligibleProduct("TZP-PC5", "Salt", "T", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC5", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-PC5");
        media().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.SKU, "TZP-PC5",
                List.of(new MediaAsset("p", "p/TZP-PC5/new.webp", ImageRole.PRIMARY, 0,
                        null, null, null, null)), "cms", null));
        assertEquals(RebuildOutcome.UPDATED, projector().rebuildOne("TZP-PC5"));
        assertEquals("p/TZP-PC5/new.webp", row("TZP-PC5").getString("primary_asset_key"));

        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-PC5"),
                new Document("$set", new Document("title", "Rock Salt").append("version", 2)));
        assertEquals(RebuildOutcome.UPDATED, projector().rebuildOne("TZP-PC5"));
        assertEquals("Rock Salt", row("TZP-PC5").getString("title"));
    }

    @Test void missing_pricing_yields_unpriced_card_never_zero() {
        seedEligibleProduct("TZP-PC6", "Sugar", "S", 1);
        assertEquals(RebuildOutcome.CREATED, projector().rebuildOne("TZP-PC6"));
        Document d = row("TZP-PC6");
        assertEquals("MISSING", d.getString("price_status"));
        assertNull(d.get("selling_price_paise"), "no fabricated price");
        assertNull(d.get("currency"));
    }

    @Test void expired_pricing_yields_status_without_amounts() {
        seedEligibleProduct("TZP-PC7", "Ghee", "G", 1);
        // write with a past-bounded window using an earlier clock, then rebuild with NOW
        PricingService earlier = new PricingService(new Tx(client), new WritePath(db),
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        earlier.upsertPrice(new UpsertPriceCommand("TZP-PC7", 100L, 200L, Currency.INR,
                null, Instant.parse("2026-01-10T00:00:00Z"), "s", null));
        projector().rebuildOne("TZP-PC7");
        Document d = row("TZP-PC7");
        assertEquals("EXPIRED", d.getString("price_status"));
        assertNull(d.get("selling_price_paise"));
    }

    @Test void product_media_fallback_when_sku_media_absent() {
        seedEligibleProduct("TZP-PC8", "Tea", "T", 1);
        media().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-PC8",
                List.of(new MediaAsset("p", "p/TZP-PC8/prod.webp", ImageRole.PRIMARY, 0,
                        null, null, null, null)), "seed", null));
        projector().rebuildOne("TZP-PC8");
        assertEquals("p/TZP-PC8/prod.webp", row("TZP-PC8").getString("primary_asset_key"));
    }

    @Test void unpublished_product_removes_projection_row() {
        seedEligibleProduct("TZP-PC9", "Ata", "A", 1);
        projector().rebuildOne("TZP-PC9");
        assertNotNull(row("TZP-PC9"));
        db.getCollection("products").updateOne(Filters.eq("_id", "TZP-PC9"),
                new Document("$set", new Document("lifecycle", "discontinued")));
        assertEquals(RebuildOutcome.REMOVED, projector().rebuildOne("TZP-PC9"));
        assertNull(row("TZP-PC9"), "stale card must not stay consumer-visible");
        assertEquals(RebuildOutcome.MISSING, projector().rebuildOne("TZP-PC9"));
    }

    @Test void unknown_sku_is_missing_without_row() {
        assertEquals(RebuildOutcome.MISSING, projector().rebuildOne("TZP-NEVER"));
        assertNull(row("TZP-NEVER"));
    }

    @Test void transient_catalog_failure_preserves_previous_projection() {
        seedEligibleProduct("TZP-PC10", "Good Title", "K", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC10", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-PC10");

        CatalogCardReadPort throwing = sku -> { throw new RuntimeException("simulated read outage"); };
        ProductCardProjectionService broken = new ProductCardProjectionService(
                throwing, pricing(), media(), db, CLOCK);
        assertThrows(RuntimeException.class, () -> broken.rebuildOne("TZP-PC10"));
        // the previously good row is fully preserved — never overwritten with empty/corrupt data
        Document d = row("TZP-PC10");
        assertEquals("Good Title", d.getString("title"));
        assertEquals(100L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals(1L, ((Number) d.get("projection_version")).longValue());
    }

    @Test void concurrent_rebuilds_converge_deterministically() throws Exception {
        seedEligibleProduct("TZP-PC11", "Race", "R", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC11", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-PC11"); // v1
        pricing().upsertPrice(new UpsertPriceCommand("TZP-PC11", 150L, 200L, Currency.INR, null, null, "s", 1L));

        ProductCardProjectionService svc = projector();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<RebuildOutcome> outcomes;
        try {
            java.util.concurrent.Callable<RebuildOutcome> attempt = () -> {
                start.await();
                return svc.rebuildOne("TZP-PC11");
            };
            Future<RebuildOutcome> a = pool.submit(attempt);
            Future<RebuildOutcome> b = pool.submit(attempt);
            start.countDown();
            outcomes = List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
        // deterministic convergence: at least one applied the change; nobody corrupted the row
        assertTrue(outcomes.contains(RebuildOutcome.UPDATED), "outcomes=" + outcomes);
        Document d = row("TZP-PC11");
        assertEquals(150L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals(2L, ((Number) d.get("projection_version")).longValue(),
                "exactly one content change: v1 -> v2, never double-applied");
    }

    @Test void HIGH_stale_price_snapshot_cannot_regress_newer_projection() {
        // PR-07 review STEP 2/5: a rebuilder holding an OLDER source snapshot loses its CAS and
        // must RE-READ ALL SOURCES — the old snapshot may never be written as a newer version.
        seedEligibleProduct("TZP-RG1", "Regress", "R", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-RG1", 10000L, 20000L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-RG1"); // row v1 @ 10000
        pricing().upsertPrice(new UpsertPriceCommand("TZP-RG1", 13000L, 20000L, Currency.INR, null, null, "s", 1L));

        PricingService real = pricing();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.tazzzo.pricing.PriceReadPort stalePrices = sku -> {
            if (calls.incrementAndGet() == 1) {
                var stale = real.findCurrentPrice(sku); // captures 13000 (v2)
                // interleave: canonical price advances AND a concurrent rebuilder lands v2@15000
                real.upsertPrice(new UpsertPriceCommand("TZP-RG1", 15000L, 20000L, Currency.INR, null, null, "s", 2L));
                projector().rebuildOne("TZP-RG1");
                return stale; // A now holds an OUTDATED snapshot
            }
            return real.findCurrentPrice(sku); // retry attempts observe fresh truth
        };
        ProductCardProjectionService a = new ProductCardProjectionService(
                new CatalogCardReader(db), stalePrices, media(), db, CLOCK);
        RebuildOutcome outcome = a.rebuildOne("TZP-RG1");

        Document d = row("TZP-RG1");
        assertEquals(15000L, ((Number) d.get("selling_price_paise")).longValue(),
                "stale 13000 snapshot must never overwrite fresh 15000");
        assertEquals(2L, ((Number) d.get("projection_version")).longValue(), "no stale v3 write");
        assertEquals(RebuildOutcome.NOOP, outcome, "retry with fresh sources converges");
        assertTrue(calls.get() >= 2, "conflict forced a full source re-read");
    }

    @Test void HIGH_stale_ineligible_observation_cannot_delete_revived_projection() {
        // PR-07 review STEP 3/6: version-guarded delete — a stale "catalog missing" observation
        // must not remove a row a concurrent rebuilder just refreshed.
        seedEligibleProduct("TZP-RV1", "Revive", "V", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-RV1", 10000L, 20000L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-RV1"); // row v1

        CatalogCardReader real = new CatalogCardReader(db);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        CatalogCardReadPort staleCatalog = sku -> {
            if (calls.incrementAndGet() == 1) {
                // interleave AFTER A observed row v1: price change + concurrent rebuild -> row v2
                pricing().upsertPrice(new UpsertPriceCommand("TZP-RV1", 12000L, 20000L, Currency.INR, null, null, "s", 1L));
                projector().rebuildOne("TZP-RV1");
                return java.util.Optional.empty(); // A's STALE ineligible observation
            }
            return real.findEligibleCard(sku);
        };
        ProductCardProjectionService a = new ProductCardProjectionService(
                staleCatalog, pricing(), media(), db, CLOCK);
        RebuildOutcome outcome = a.rebuildOne("TZP-RV1");

        Document d = row("TZP-RV1");
        assertNotNull(d, "fresh projection must survive the stale delete attempt");
        assertEquals(12000L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals(2L, ((Number) d.get("projection_version")).longValue());
        assertEquals(RebuildOutcome.NOOP, outcome, "delete CAS failed; retry converged on truth");
    }

    @Test void simultaneous_create_race_converges_to_one_row() throws Exception {
        seedEligibleProduct("TZP-CR1", "CreateRace", "C", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-CR1", 100L, 200L, Currency.INR, null, null, "s", null));
        ProductCardProjectionService svc = projector();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<RebuildOutcome> outcomes;
        try {
            java.util.concurrent.Callable<RebuildOutcome> attempt = () -> {
                start.await();
                return svc.rebuildOne("TZP-CR1");
            };
            Future<RebuildOutcome> a = pool.submit(attempt);
            Future<RebuildOutcome> b = pool.submit(attempt);
            start.countDown();
            outcomes = List.of(a.get(), b.get()); // no raw Mongo exception may escape
        } finally {
            pool.shutdownNow();
        }
        assertTrue(outcomes.contains(RebuildOutcome.CREATED), "outcomes=" + outcomes);
        assertEquals(1, db.getCollection("product_card_base").countDocuments(Filters.eq("sku_id", "TZP-CR1")));
        assertEquals(1L, ((Number) row("TZP-CR1").get("projection_version")).longValue());
    }

    @Test void projection_version_overflow_fails_typed_and_preserves_row() {
        seedEligibleProduct("TZP-OV1", "Overflow", "O", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-OV1", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-OV1");
        db.getCollection("product_card_base").updateOne(Filters.eq("sku_id", "TZP-OV1"),
                new Document("$set", new Document("projection_version", Long.MAX_VALUE)));
        pricing().upsertPrice(new UpsertPriceCommand("TZP-OV1", 150L, 200L, Currency.INR, null, null, "s", 1L));
        assertThrows(com.tazzzo.commerce.read.ProjectionConflictException.class,
                () -> projector().rebuildOne("TZP-OV1"));
        Document d = row("TZP-OV1");
        assertEquals(Long.MAX_VALUE, ((Number) d.get("projection_version")).longValue());
        assertEquals(100L, ((Number) d.get("selling_price_paise")).longValue(), "no wrap, no write");
    }

    @Test void inactive_sku_media_is_suppressed_not_product_fallback() {
        // STEP 9 frozen policy: MISSING -> PRODUCT fallback; INACTIVE -> explicit suppression.
        seedEligibleProduct("TZP-SUP1", "Suppress", "S", 1);
        media().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-SUP1",
                List.of(new MediaAsset("pp", "p/TZP-SUP1/prod.webp", ImageRole.PRIMARY, 0,
                        null, null, null, null)), "seed", null));
        media().upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.SKU, "TZP-SUP1",
                List.of(new MediaAsset("ps", "p/TZP-SUP1/sku.webp", ImageRole.PRIMARY, 0,
                        null, null, null, null)), "seed", null));
        db.getCollection("media_refs").updateOne(
                Filters.and(Filters.eq("owner_type", "SKU"), Filters.eq("owner_id", "TZP-SUP1")),
                new Document("$set", new Document("active", false)));
        projector().rebuildOne("TZP-SUP1");
        assertNull(row("TZP-SUP1").getString("primary_asset_key"),
                "INACTIVE SKU media suppresses imagery; PRODUCT media must NOT leak through");
    }

    @Test void pricing_and_media_read_failures_preserve_previous_row() {
        seedEligibleProduct("TZP-SF1", "SourceFail", "F", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-SF1", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-SF1");

        com.tazzzo.pricing.PriceReadPort badPrices = sku -> { throw new RuntimeException("pricing outage"); };
        assertThrows(RuntimeException.class, () -> new ProductCardProjectionService(
                new CatalogCardReader(db), badPrices, media(), db, CLOCK).rebuildOne("TZP-SF1"));

        com.tazzzo.media.MediaReadPort badMedia = (t, id) -> { throw new RuntimeException("media outage"); };
        assertThrows(RuntimeException.class, () -> new ProductCardProjectionService(
                new CatalogCardReader(db), pricing(), badMedia, db, CLOCK).rebuildOne("TZP-SF1"));

        Document d = row("TZP-SF1");
        assertEquals(100L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals(1L, ((Number) d.get("projection_version")).longValue(), "row fully preserved");
    }

    @Test void corrupt_persisted_row_fails_fast_never_defaults() {
        seedEligibleProduct("TZP-CX1", "Corrupt", "X", 1);
        pricing().upsertPrice(new UpsertPriceCommand("TZP-CX1", 100L, 200L, Currency.INR, null, null, "s", null));
        projector().rebuildOne("TZP-CX1");
        db.getCollection("product_card_base").updateOne(Filters.eq("sku_id", "TZP-CX1"),
                new Document("$unset", new Document("source_versions", "")));
        assertThrows(IllegalStateException.class, () -> projector().rebuildOne("TZP-CX1"),
                "corruption must surface loudly, never default into valid-looking state");
    }

    @Test void bootstrap_idempotent_and_sku_unique_index_present() {
        assertDoesNotThrow(() -> schemaBootstrap.bootstrap(db));
        boolean unique = false;
        for (Document ix : db.getCollection("product_card_base").listIndexes()) {
            Document key = (Document) ix.get("key");
            if (key != null && key.containsKey("sku_id")) {
                unique = Boolean.TRUE.equals(ix.getBoolean("unique"));
            }
        }
        assertTrue(unique, "(sku_id) unique index present");
    }
}
