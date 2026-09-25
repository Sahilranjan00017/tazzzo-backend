package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.OffersService;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.money.Currency;
import com.tazzzo.pricing.PriceConflictException;
import com.tazzzo.pricing.PriceLookup;
import com.tazzzo.pricing.PriceStatus;
import com.tazzzo.pricing.PricingService;
import com.tazzzo.pricing.UpsertPriceCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-03 Pricing foundation — Testcontainers integration against Mongo 7.
 * Verifies canonical price persistence, exact int64 paise, legacy offers untouched,
 * event-before-state atomicity, unique key, optimistic concurrency, read states, and
 * additive schema/index idempotency.
 */
class PricingFoundationIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private PricingService service(Instant now) {
        return new PricingService(new Tx(client), new WritePath(db), Clock.fixed(now, ZoneOffset.UTC));
    }

    private UpsertPriceCommand create(String sku, long selling, long mrp) {
        return new UpsertPriceCommand(sku, selling, mrp, Currency.INR, null, null, "seed", null);
    }

    @Test void canonical_price_persists_with_exact_int64_paise() {
        PricingService svc = service(NOW);
        long v = svc.upsertPrice(create("TZP-100002", 26500L, 30000L));
        assertEquals(1L, v);

        Document d = db.getCollection("price_current")
                .find(Filters.and(Filters.eq("sku_id", "TZP-100002"), Filters.eq("currency", "INR"))).first();
        assertNotNull(d);
        assertEquals(26500L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals(30000L, ((Number) d.get("mrp_paise")).longValue());
        assertEquals(1L, ((Number) d.get("version")).longValue());
        assertTrue(d.getBoolean("active"));

        PriceLookup lk = svc.findCurrentPrice("TZP-100002");
        assertEquals(PriceStatus.ACTIVE, lk.status());
        assertTrue(lk.isUsable());
        assertEquals(26500L, lk.price().sellingPricePaise());
        assertEquals(3500L, lk.price().discountAmountPaise());
    }

    @Test void price_event_appended_and_atomic_with_state() {
        PricingService svc = service(NOW);
        long before = db.getCollection("price_events").countDocuments(Filters.eq("sku_id", "TZP-EVT"));
        svc.upsertPrice(create("TZP-EVT", 100L, 200L));
        long after = db.getCollection("price_events").countDocuments(Filters.eq("sku_id", "TZP-EVT"));
        assertEquals(before + 1, after, "one new-shape ledger row appended");
        // audit event also written to product_events (C-3)
        assertTrue(db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-EVT"), Filters.eq("type", "PRICE_UPDATED"))) >= 1);
    }

    @Test void legacy_offers_and_price_events_are_untouched() {
        // Legacy raw commercial input via the existing OffersService — DIFFERENT collection/shape.
        new OffersService(new Tx(client), new WritePath(db))
                .upsertOffer("TZP-LEG", "crawler", "sellerA", "web", 199, true);
        Document offer = db.getCollection("offers_current").find(Filters.eq("product_id", "TZP-LEG")).first();
        assertNotNull(offer);
        assertEquals(199, ((Number) offer.get("price")).intValue(), "legacy int price unchanged");
        assertFalse(offer.containsKey("selling_price_paise"), "legacy row NOT converted to paise");
        // Pricing never wrote a canonical price for a legacy-only SKU:
        assertEquals(PriceStatus.MISSING, service(NOW).findCurrentPrice("TZP-LEG").status());
    }

    @Test void unique_key_rejects_duplicate_create() {
        PricingService svc = service(NOW);
        svc.upsertPrice(create("TZP-DUP", 100L, 200L));
        assertThrows(PriceConflictException.class, () -> svc.upsertPrice(create("TZP-DUP", 150L, 250L)));
    }

    @Test void optimistic_concurrency_stale_update_rejected_and_rolls_back() {
        PricingService svc = service(NOW);
        svc.upsertPrice(create("TZP-CAS", 100L, 200L)); // v1
        long v2 = svc.upsertPrice(new UpsertPriceCommand("TZP-CAS", 120L, 200L, Currency.INR, null, null, "s", 1L));
        assertEquals(2L, v2);

        long ledgerBefore = db.getCollection("price_events").countDocuments(Filters.eq("sku_id", "TZP-CAS"));
        // stale writer still expects version 1
        assertThrows(PriceConflictException.class,
                () -> svc.upsertPrice(new UpsertPriceCommand("TZP-CAS", 999L, 1000L, Currency.INR, null, null, "s", 1L)));
        // state unchanged and NO orphan ledger row (transaction rolled back)
        Document d = db.getCollection("price_current").find(Filters.eq("sku_id", "TZP-CAS")).first();
        assertEquals(2L, ((Number) d.get("version")).longValue());
        assertEquals(120L, ((Number) d.get("selling_price_paise")).longValue());
        assertEquals(ledgerBefore, db.getCollection("price_events").countDocuments(Filters.eq("sku_id", "TZP-CAS")));
    }

    @Test void read_reports_expired_and_inactive() {
        // effectiveTo in the past relative to the read clock -> EXPIRED
        PricingService writer = service(Instant.parse("2026-01-01T00:00:00Z"));
        writer.upsertPrice(new UpsertPriceCommand("TZP-EXP", 100L, 200L, Currency.INR,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-10T00:00:00Z"), "s", null));
        PricingService laterReader = service(Instant.parse("2026-02-01T00:00:00Z"));
        assertEquals(PriceStatus.EXPIRED, laterReader.findCurrentPrice("TZP-EXP").status());

        // flip active=false directly -> INACTIVE
        db.getCollection("price_current").updateOne(Filters.eq("sku_id", "TZP-EXP"),
                new Document("$set", new Document("active", false)));
        assertEquals(PriceStatus.INACTIVE, laterReader.findCurrentPrice("TZP-EXP").status());
    }

    @Test void missing_price_reads_missing() {
        assertEquals(PriceStatus.MISSING, service(NOW).findCurrentPrice("TZP-NONE").status());
    }

    @Test void legacy_shaped_price_event_persists_without_conversion() {
        // A legacy-shaped ledger row (product_id/source/seller/channel/price/ts) must remain valid.
        Document legacy = new Document("product_id", "TZP-OLD").append("source", "crawler")
                .append("seller", "s").append("channel", "web").append("price", 150).append("ts", new Date());
        db.getCollection("price_events").insertOne(legacy);
        Document back = db.getCollection("price_events").find(
                Filters.and(Filters.eq("product_id", "TZP-OLD"), Filters.exists("selling_price_paise", false))).first();
        assertNotNull(back);
        assertEquals(150, ((Number) back.get("price")).intValue());
    }

    @Test void future_dated_write_rejected_and_current_price_preserved() {
        // STEP 3 (PR-03 review): scheduling tomorrow's price must NOT replace today's active price.
        PricingService svc = service(NOW);
        svc.upsertPrice(new UpsertPriceCommand("TZP-FUT", 10000L, 12000L, Currency.INR,
                NOW.minusSeconds(86400), null, "seed", null)); // ₹100, from yesterday, open-ended

        long ledgerBefore = db.getCollection("price_events").countDocuments(Filters.eq("sku_id", "TZP-FUT"));
        assertThrows(com.tazzzo.pricing.InvalidPriceException.class,
                () -> svc.upsertPrice(new UpsertPriceCommand("TZP-FUT", 11000L, 12000L, Currency.INR,
                        NOW.plusSeconds(86400), null, "operator", 1L))); // ₹110 from tomorrow -> rejected

        // the live price is untouched: still ACTIVE, still v1, still ₹100 — no orphan ledger row.
        PriceLookup lk = svc.findCurrentPrice("TZP-FUT");
        assertEquals(PriceStatus.ACTIVE, lk.status());
        assertEquals(10000L, lk.price().sellingPricePaise());
        assertEquals(1L, lk.price().version());
        assertEquals(ledgerBefore, db.getCollection("price_events").countDocuments(Filters.eq("sku_id", "TZP-FUT")));
    }

    @Test void effective_from_exactly_now_is_allowed_boundary() {
        PricingService svc = service(NOW);
        long v = svc.upsertPrice(new UpsertPriceCommand("TZP-NOWB", 100L, 200L, Currency.INR,
                NOW, null, "seed", null)); // from == now: inclusive, immediate
        assertEquals(1L, v);
        assertEquals(PriceStatus.ACTIVE, svc.findCurrentPrice("TZP-NOWB").status());
    }

    @Test void already_expired_effective_to_rejected() {
        PricingService svc = service(NOW);
        assertThrows(com.tazzzo.pricing.InvalidPriceException.class,
                () -> svc.upsertPrice(new UpsertPriceCommand("TZP-DEAD", 100L, 200L, Currency.INR,
                        NOW.minusSeconds(7200), NOW, "seed", null))); // to == now: exclusive -> dead on arrival
    }

    @Test void expired_price_can_be_replaced_via_cas() {
        // STEP 4: an EXPIRED row does not reactivate; a replacement write (expectedVersion=current)
        // succeeds and becomes the new ACTIVE price.
        PricingService writer = service(Instant.parse("2026-01-01T00:00:00Z"));
        writer.upsertPrice(new UpsertPriceCommand("TZP-REPL", 100L, 200L, Currency.INR,
                null, Instant.parse("2026-01-10T00:00:00Z"), "s", null)); // v1, expires Jan 10
        PricingService later = service(NOW); // June: expired
        assertEquals(PriceStatus.EXPIRED, later.findCurrentPrice("TZP-REPL").status());
        long v2 = later.upsertPrice(new UpsertPriceCommand("TZP-REPL", 120L, 200L, Currency.INR,
                null, null, "s", 1L));
        assertEquals(2L, v2);
        PriceLookup lk = later.findCurrentPrice("TZP-REPL");
        assertEquals(PriceStatus.ACTIVE, lk.status());
        assertEquals(120L, lk.price().sellingPricePaise());
    }

    @Test void bson_money_fields_are_int64() {
        // STEP 9: paise must persist as BSON int64 (Long), never int32/double/string.
        service(NOW).upsertPrice(create("TZP-BSON", 26500L, 30000L));
        Document d = db.getCollection("price_current").find(Filters.eq("sku_id", "TZP-BSON")).first();
        assertInstanceOf(Long.class, d.get("selling_price_paise"));
        assertInstanceOf(Long.class, d.get("mrp_paise"));
        assertInstanceOf(Long.class, d.get("version"));
    }

    @Test void bootstrap_is_idempotent_and_creates_unique_index() {
        // @BeforeAll already bootstrapped; re-running must not throw and index must be unique.
        assertDoesNotThrow(() -> schemaBootstrap.bootstrap(db));
        boolean unique = false;
        for (Document ix : db.getCollection("price_current").listIndexes()) {
            Document key = (Document) ix.get("key");
            if (key != null && key.containsKey("sku_id") && key.containsKey("currency")) {
                unique = Boolean.TRUE.equals(ix.getBoolean("unique"));
            }
        }
        assertTrue(unique, "(sku_id, currency) unique index present");
    }
}
