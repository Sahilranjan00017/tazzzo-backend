package com.tazzzo.media;

import com.tazzzo.commerce.contract.ImageRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 24 UNIT: set/asset invariants, no Mongo. */
class MediaSetValidationTest {

    private MediaAsset asset(String id, String key, ImageRole role, int order) {
        return new MediaAsset(id, key, role, order, null, null, null, null);
    }

    private MediaSet set(List<MediaAsset> assets) {
        return new MediaSet(MediaOwnerType.PRODUCT, "TZP-1", 1, true, assets);
    }

    @Test void valid_set_with_primary_and_gallery() {
        MediaSet s = set(List.of(
                asset("a1", "p/TZP-1/front.webp", ImageRole.PRIMARY, 0),
                asset("a2", "p/TZP-1/back.webp", ImageRole.GALLERY, 1)));
        assertEquals("a1", s.primary().orElseThrow().assetId());
        assertEquals(List.of("a1", "a2"),
                s.orderedAssets().stream().map(MediaAsset::assetId).toList());
    }

    @Test void empty_assets_is_valid_cleared_media() {
        MediaSet s = set(List.of());
        assertTrue(s.primary().isEmpty());
        assertTrue(s.orderedAssets().isEmpty());
    }

    @Test void missing_primary_is_representable() {
        MediaSet s = set(List.of(asset("g1", "p/TZP-1/g1.webp", ImageRole.GALLERY, 0)));
        assertTrue(s.primary().isEmpty());
    }

    @Test void blank_owner_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaSet(MediaOwnerType.PRODUCT, " ", 1, true, List.of()));
    }

    @Test void null_owner_type_rejected() {
        assertThrows(NullPointerException.class,
                () -> new MediaSet(null, "TZP-1", 1, true, List.of()));
    }

    @Test void duplicate_asset_id_rejected() {
        assertThrows(IllegalArgumentException.class, () -> set(List.of(
                asset("dup", "p/a.webp", ImageRole.PRIMARY, 0),
                asset("dup", "p/b.webp", ImageRole.GALLERY, 1))));
    }

    @Test void multiple_primary_rejected() {
        assertThrows(IllegalArgumentException.class, () -> set(List.of(
                asset("a1", "p/a.webp", ImageRole.PRIMARY, 0),
                // second PRIMARY: also at a non-zero order so the primary-order rule triggers too
                asset("a2", "p/b.webp", ImageRole.PRIMARY, 1))));
    }

    @Test void primary_must_have_order_zero() {
        assertThrows(IllegalArgumentException.class, () -> set(List.of(
                asset("g", "p/g.webp", ImageRole.GALLERY, 0),
                asset("p", "p/p.webp", ImageRole.PRIMARY, 1))));
    }

    @Test void duplicate_sort_order_rejected() {
        assertThrows(IllegalArgumentException.class, () -> set(List.of(
                asset("a1", "p/a.webp", ImageRole.GALLERY, 3),
                asset("a2", "p/b.webp", ImageRole.GALLERY, 3))));
    }

    @Test void negative_order_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> asset("a1", "p/a.webp", ImageRole.GALLERY, -1));
    }

    @Test void ordering_is_deterministic_regardless_of_input_order() {
        MediaSet s = set(List.of(
                asset("g2", "p/g2.webp", ImageRole.GALLERY, 5),
                asset("p1", "p/p1.webp", ImageRole.PRIMARY, 0),
                asset("g1", "p/g1.webp", ImageRole.GALLERY, 2)));
        assertEquals(List.of("p1", "g1", "g2"),
                s.orderedAssets().stream().map(MediaAsset::assetId).toList());
    }

    @Test void unsafe_asset_keys_rejected() {
        for (String bad : List.of("/leading", "a//b.webp", "a/../secret", "", " ",
                "https://evil.example/x.png", "a b.webp", "a\\b.webp")) {
            assertThrows(IllegalArgumentException.class,
                    () -> asset("a1", bad, ImageRole.GALLERY, 0), "should reject: " + bad);
        }
    }

    @Test void one_sided_dimensions_rejected_both_sided_ok() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, null, 100, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, null, null, 100, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, null, 0, 100, null));
        assertDoesNotThrow(
                () -> new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, null, 800, 600, null));
    }

    @Test void content_type_allowlist_enforced() {
        for (String ok : MediaAsset.ALLOWED_CONTENT_TYPES) {
            assertDoesNotThrow(() -> new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, null, null, null, ok));
        }
        for (String bad : List.of("image/svg+xml", "text/html", "application/octet-stream", "image/gif")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, null, null, null, bad));
        }
    }

    @Test void alt_text_bounds_and_plain_text() {
        assertThrows(IllegalArgumentException.class, () -> new MediaAsset(
                "a", "k.webp", ImageRole.GALLERY, 0, "x".repeat(301), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaAsset(
                "a", "k.webp", ImageRole.GALLERY, 0, "<img onerror=alert(1)>", null, null, null));
        MediaAsset trimmed = new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, "  front pack  ", null, null, null);
        assertEquals("front pack", trimmed.altText());
        // whitespace-only collapses to null
        assertNull(new MediaAsset("a", "k.webp", ImageRole.GALLERY, 0, "   ", null, null, null).altText());
    }

    @Test void version_and_asset_cap_validated() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaSet(MediaOwnerType.SKU, "TZP-1", 0, true, List.of()));
        List<MediaAsset> tooMany = IntStream.range(0, 51)
                .mapToObj(i -> asset("a" + i, "k" + i + ".webp", ImageRole.GALLERY, i)).toList();
        assertThrows(IllegalArgumentException.class, () -> set(tooMany));
    }

    @Test void command_validation_rejects_bad_expected_version() {
        assertThrows(InvalidMediaException.class, () -> MediaService.validateCommand(
                new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-1", List.of(), "seed", 0L)));
        assertThrows(InvalidMediaException.class, () -> MediaService.validateCommand(
                new UpsertMediaSetCommand(MediaOwnerType.PRODUCT, "TZP-1", null, "seed", null)));
    }
}
