package com.tazzzo.catalog;

import com.mongodb.client.model.Filters;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.ImageRole;
import com.tazzzo.media.MediaAsset;
import com.tazzzo.media.MediaConflictException;
import com.tazzzo.media.MediaLookup;
import com.tazzzo.media.MediaNotFoundException;
import com.tazzzo.media.MediaOwnerType;
import com.tazzzo.media.MediaService;
import com.tazzzo.media.UpsertMediaSetCommand;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-05 Media foundation — Testcontainers integration against Mongo 7. Proves canonical
 * persistence, owner uniqueness, whole-set atomic replacement, CAS + rollback, PRODUCT/SKU
 * owner independence, missing/inactive distinction, and BSON shape.
 */
class MediaFoundationIT extends AbstractMongoIT {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private MediaService service() {
        return new MediaService(new Tx(client), new WritePath(db), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MediaAsset primary(String id, String key) {
        return new MediaAsset(id, key, ImageRole.PRIMARY, 0, "front of pack", 800, 800, "image/webp");
    }

    private MediaAsset gallery(String id, String key, int order) {
        return new MediaAsset(id, key, ImageRole.GALLERY, order, null, null, null, null);
    }

    private UpsertMediaSetCommand create(MediaOwnerType type, String owner, List<MediaAsset> assets) {
        return new UpsertMediaSetCommand(type, owner, assets, "seed", null);
    }

    @Test void create_and_read_present_with_deterministic_order() {
        MediaService svc = service();
        long v = svc.upsertMediaSet(create(MediaOwnerType.PRODUCT, "TZP-MED1", List.of(
                gallery("g1", "p/TZP-MED1/g1.webp", 2),
                primary("p1", "p/TZP-MED1/front.webp"),
                gallery("g2", "p/TZP-MED1/g2.webp", 1))));
        assertEquals(1L, v);
        MediaLookup lk = svc.findMedia(MediaOwnerType.PRODUCT, "TZP-MED1");
        assertTrue(lk.isPresent());
        assertEquals("p1", lk.mediaSet().primary().orElseThrow().assetId());
        assertEquals(List.of("p1", "g2", "g1"),
                lk.mediaSet().orderedAssets().stream().map(MediaAsset::assetId).toList());
        assertEquals("front of pack", lk.mediaSet().primary().orElseThrow().altText());
    }

    @Test void whole_set_replacement_is_atomic_and_complete() {
        MediaService svc = service();
        svc.upsertMediaSet(create(MediaOwnerType.PRODUCT, "TZP-MED2", List.of(
                primary("old-p", "p/TZP-MED2/old.webp"), gallery("old-g", "p/TZP-MED2/oldg.webp", 1))));
        long v2 = svc.upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-MED2",
                List.of(primary("new-p", "p/TZP-MED2/new.webp")), "cms", 1L));
        assertEquals(2L, v2);
        MediaLookup lk = svc.findMedia(MediaOwnerType.PRODUCT, "TZP-MED2");
        // complete replacement: old assets are GONE, not merged
        assertEquals(1, lk.mediaSet().assets().size());
        assertEquals("new-p", lk.mediaSet().primary().orElseThrow().assetId());
    }

    @Test void stale_cas_rejected_with_no_audit_residue() {
        MediaService svc = service();
        svc.upsertMediaSet(create(MediaOwnerType.PRODUCT, "TZP-MED3", List.of(primary("p", "k.webp"))));
        svc.upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-MED3",
                List.of(), "cms", 1L)); // v2, cleared
        long auditBefore = db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-MED3"), Filters.eq("type", "MEDIA_SET_UPDATED")));
        assertThrows(MediaConflictException.class, () -> svc.upsertMediaSet(
                new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-MED3",
                        List.of(primary("x", "x.webp")), "cms", 1L)));
        MediaLookup lk = svc.findMedia(MediaOwnerType.PRODUCT, "TZP-MED3");
        assertEquals(2, lk.mediaSet().version());
        assertTrue(lk.mediaSet().assets().isEmpty(), "cleared state preserved");
        assertEquals(auditBefore, db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-MED3"), Filters.eq("type", "MEDIA_SET_UPDATED"))));
    }

    @Test void duplicate_create_rejected_without_orphan_audit() {
        MediaService svc = service();
        svc.upsertMediaSet(create(MediaOwnerType.SKU, "TZP-MED4", List.of()));
        long auditBefore = db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-MED4"), Filters.eq("type", "MEDIA_SET_UPDATED")));
        assertThrows(MediaConflictException.class,
                () -> svc.upsertMediaSet(create(MediaOwnerType.SKU, "TZP-MED4", List.of())));
        assertEquals(auditBefore, db.getCollection("product_events").countDocuments(
                Filters.and(Filters.eq("product_id", "TZP-MED4"), Filters.eq("type", "MEDIA_SET_UPDATED"))));
    }

    @Test void update_of_missing_set_is_not_found() {
        assertThrows(MediaNotFoundException.class, () -> service().upsertMediaSet(
                new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-GHOST", List.of(), "cms", 1L)));
    }

    @Test void product_and_sku_owners_are_independent() {
        MediaService svc = service();
        svc.upsertMediaSet(create(MediaOwnerType.PRODUCT, "TZP-MED5",
                List.of(primary("prod-img", "p/TZP-MED5/product.webp"))));
        svc.upsertMediaSet(create(MediaOwnerType.SKU, "TZP-MED5",
                List.of(primary("sku-img", "p/TZP-MED5/sku.webp"))));
        assertEquals("prod-img", svc.findMedia(MediaOwnerType.PRODUCT, "TZP-MED5")
                .mediaSet().primary().orElseThrow().assetId());
        assertEquals("sku-img", svc.findMedia(MediaOwnerType.SKU, "TZP-MED5")
                .mediaSet().primary().orElseThrow().assetId());
    }

    @Test void missing_vs_inactive_distinguished() {
        MediaService svc = service();
        assertEquals(MediaLookup.Status.MISSING, svc.findMedia(MediaOwnerType.PRODUCT, "TZP-NEVER").status());
        svc.upsertMediaSet(create(MediaOwnerType.PRODUCT, "TZP-MED6", List.of()));
        db.getCollection("media_refs").updateOne(
                Filters.and(Filters.eq("owner_type", "PRODUCT"), Filters.eq("owner_id", "TZP-MED6")),
                new Document("$set", new Document("active", false)));
        assertEquals(MediaLookup.Status.INACTIVE, svc.findMedia(MediaOwnerType.PRODUCT, "TZP-MED6").status());
    }

    @Test void bson_shape_is_correct() {
        service().upsertMediaSet(create(MediaOwnerType.PRODUCT, "TZP-BSON3",
                List.of(primary("p", "p/TZP-BSON3/f.webp"))));
        Document d = db.getCollection("media_refs").find(Filters.eq("owner_id", "TZP-BSON3")).first();
        assertInstanceOf(Long.class, d.get("version"));
        assertEquals("PRODUCT", d.getString("owner_type"));
        Document a = d.getList("assets", Document.class).get(0);
        assertEquals("PRIMARY", a.getString("role"));
        assertInstanceOf(Integer.class, a.get("sort_order"));
        assertInstanceOf(Integer.class, a.get("width"));
        assertEquals("image/webp", a.getString("content_type"));
        assertFalse(a.containsKey("url"), "no URL is ever persisted — keys only");
    }

    @Test void concurrent_cas_writers_produce_exactly_one_winner() throws Exception {
        MediaService svc = service();
        svc.upsertMediaSet(create(MediaOwnerType.PRODUCT, "TZP-MEDRACE", List.of())); // v1
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable attempt = () -> {
                try {
                    start.await();
                    svc.upsertMediaSet(new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-MEDRACE",
                            List.of(gallery("g-" + Thread.currentThread().getName(), "k.webp", 1)), "cms", 1L));
                    wins.incrementAndGet();
                } catch (MediaConflictException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Future<?> a = pool.submit(attempt);
            Future<?> b = pool.submit(attempt);
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "exactly one writer may advance v1 -> v2");
        assertEquals(1, conflicts.get(), "the stale writer gets a typed conflict");
        assertEquals(2, service().findMedia(MediaOwnerType.PRODUCT, "TZP-MEDRACE").mediaSet().version());
    }

    @Test void bootstrap_idempotent_and_media_unique_index_present() {
        assertDoesNotThrow(() -> schemaBootstrap.bootstrap(db));
        boolean unique = false;
        for (Document ix : db.getCollection("media_refs").listIndexes()) {
            Document key = (Document) ix.get("key");
            if (key != null && key.containsKey("owner_type") && key.containsKey("owner_id")) {
                unique = Boolean.TRUE.equals(ix.getBoolean("unique"));
            }
        }
        assertTrue(unique, "(owner_type, owner_id) unique index present");
    }
}
