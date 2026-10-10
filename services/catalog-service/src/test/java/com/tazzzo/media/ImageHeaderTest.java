package com.tazzzo.media;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Header-only dimension parsing and the verifier's pixel/dimension/declared-size/extension checks, on synthetic images. */
class ImageHeaderTest {

    private static final MediaUploadPolicy POLICY = new MediaUploadPolicy(1_000_000);

    private static MediaStorage storage(long size, String type, byte[] head) {
        return new MediaStorage() {
            @Override public boolean enabled() { return true; }
            @Override public UploadTarget createUpload(String k, String c, long s) { throw new UnsupportedOperationException(); }
            @Override public Optional<StoredObject> inspect(String k) { return Optional.of(new StoredObject(size, type, head)); }
        };
    }

    private static MediaIngestVerifier verifier(long size, String type, byte[] head) {
        return new MediaIngestVerifier(storage(size, type, head), POLICY, 50_000_000L, 20_000, true);
    }

    private static void ok(String key, String type, byte[] head, Integer w, Integer h) {
        verifier(head.length, type, head).verify(key, type, w, h);
    }

    private static void refused(String key, String type, byte[] head, Integer w, Integer h, String message) {
        assertThatThrownBy(() -> verifier(head.length, type, head).verify(key, type, w, h))
                .isInstanceOf(InvalidMediaException.class).hasMessageContaining(message);
    }

    @Test
    void valid_headers_report_their_real_dimensions() {
        assertThat(ImageHeader.read("image/png", TestImages.png(640, 480))).isEqualTo(new ImageHeader.Dimensions(640, 480));
        assertThat(ImageHeader.read("image/jpeg", TestImages.jpeg(1024, 768))).isEqualTo(new ImageHeader.Dimensions(1024, 768));
        assertThat(ImageHeader.read("image/webp", TestImages.webpLossy(320, 200))).isEqualTo(new ImageHeader.Dimensions(320, 200));
        assertThat(ImageHeader.read("image/webp", TestImages.webpLossless(16383, 1))).isEqualTo(new ImageHeader.Dimensions(16383, 1));
        assertThat(ImageHeader.read("image/webp", TestImages.webpExtended(4000, 3000))).isEqualTo(new ImageHeader.Dimensions(4000, 3000));
    }

    @Test
    void jpeg_with_many_app_segments_before_sof_is_found_within_the_bounded_scan_and_beyond_it_is_refused() {
        assertThat(ImageHeader.read("image/jpeg", TestImages.jpegWithApp(800, 600, 300))).isEqualTo(new ImageHeader.Dimensions(800, 600));   // ~30 KB of APPn
        byte[] tooFar = TestImages.jpegWithApp(800, 600, 700);   // SOF after ~70 KB: outside the 64 KiB scan
        assertThatThrownBy(() -> ImageHeader.read("image/jpeg", tooFar)).isInstanceOf(InvalidMediaException.class).hasMessageContaining("not found");
    }

    @Test
    void truncated_or_garbage_headers_that_pass_the_magic_bytes_are_refused() {
        byte[] png = TestImages.png(10, 10);
        for (int cut : new int[]{8, 12, 16, 23}) {
            refused("p/o/1/x.png", "image/png", Arrays.copyOf(png, cut), null, null, "PNG header");
        }
        byte[] badIhdr = png.clone();
        badIhdr[12] = 'X';
        refused("p/o/1/x.png", "image/png", badIhdr, null, null, "PNG header");
        refused("p/o/1/x.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, null, null, "JPEG");
        byte[] jpeg = TestImages.jpeg(10, 10);
        refused("p/o/1/x.jpg", "image/jpeg", Arrays.copyOf(jpeg, jpeg.length - 6), null, null, "truncated");
        refused("p/o/1/x.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2}, null, null, "no frame header");
        refused("p/o/1/x.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 'A', 'B', 'C'}, null, null, "JPEG");
        byte[] webp = TestImages.webpLossy(10, 10);
        refused("p/o/1/x.webp", "image/webp", Arrays.copyOf(webp, 20), null, null, "WebP header");
        byte[] badStart = webp.clone();
        badStart[23] = 0;
        refused("p/o/1/x.webp", "image/webp", badStart, null, null, "malformed");
        byte[] unknownChunk = webp.clone();
        unknownChunk[15] = 'Z';
        refused("p/o/1/x.webp", "image/webp", unknownChunk, null, null, "not supported");
        refused("p/o/1/x.png", "image/png", TestImages.png(0, 10), null, null, "zero dimension");
        refused("p/o/1/x.jpg", "image/jpeg", TestImages.jpeg(10, 0), null, null, "zero dimension");
    }

    @Test
    void declared_dimensions_must_equal_the_real_ones_when_given() {
        ok("p/o/1/x.png", "image/png", TestImages.png(640, 480), 640, 480);
        ok("p/o/1/x.png", "image/png", TestImages.png(640, 480), null, null);
        refused("p/o/1/x.png", "image/png", TestImages.png(640, 480), 640, 481, "do not match");
        refused("p/o/1/x.png", "image/png", TestImages.png(640, 480), 641, 480, "do not match");
        refused("p/o/1/x.jpg", "image/jpeg", TestImages.jpeg(100, 50), 50, 100, "do not match");
        refused("p/o/1/x.webp", "image/webp", TestImages.webpLossless(99, 77), 99, 78, "do not match");
    }

    @Test
    void decompression_bomb_dimensions_are_refused_by_pixel_count_and_by_side() {
        refused("p/o/1/x.png", "image/png", TestImages.png(60_000, 60_000), null, null, "exceed");
        refused("p/o/1/x.png", "image/png", TestImages.png(4_000_000_000L, 1), null, null, "exceed");   // unsigned 32-bit side
        refused("p/o/1/x.jpg", "image/jpeg", TestImages.jpeg(65_535, 65_535), null, null, "exceed");
        refused("p/o/1/x.png", "image/png", TestImages.png(15_000, 15_000), null, null, "more than the maximum");   // sides fine, 225 MP
        ok("p/o/1/x.png", "image/png", TestImages.png(7_000, 7_000), null, null);   // 49 MP
        refused("p/o/1/x.png", "image/png", TestImages.png(7_100, 7_100), null, null, "more than the maximum");   // 50.4 MP
        refused("p/o/1/x.png", "image/png", TestImages.png(20_001, 1), null, null, "exceed");
        ok("p/o/1/x.png", "image/png", TestImages.png(20_000, 1), null, null);
    }

    @Test
    void the_bounds_are_configurable() {
        MediaIngestVerifier tight = new MediaIngestVerifier(storage(100, "image/png", TestImages.png(100, 100)), POLICY, 5_000, 200, true);
        assertThatThrownBy(() -> tight.verify("p/o/1/x.png", "image/png")).isInstanceOf(InvalidMediaException.class).hasMessageContaining("5000 pixels");
        assertThatThrownBy(() -> new MediaIngestVerifier(storage(1, null, new byte[0]), POLICY, 0, 10, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mime_extension_and_sniffed_type_must_all_agree_and_non_allowed_formats_are_refused() {
        assertThatThrownBy(() -> verifier(100, "image/png", TestImages.png(10, 10)).verify("p/o/1/x.png", "image/jpeg"))
                .isInstanceOf(InvalidMediaException.class).hasMessageContaining("declared contentType");   // declared vs sniffed
        refused("p/o/1/x.jpg", null, TestImages.png(10, 10), null, null, "extension");                       // wrong extension on png bytes
        refused("p/o/1/x.png", null, TestImages.webpLossy(10, 10), null, null, "extension");
        refused("p/o/1/noext", null, TestImages.png(10, 10), null, null, "extension");
        refused("p/o/1/x.svg", null, TestImages.png(10, 10), null, null, "extension");
        ok("p/o/1/x.jpeg", "image/jpeg", TestImages.jpeg(10, 10), null, null);
        ok("p/o/1/X.JPG", "image/jpeg", TestImages.jpeg(10, 10), null, null);
        // stored label differs from the sniffed type
        assertThatThrownBy(() -> verifier(100, "text/html", TestImages.png(10, 10)).verify("p/o/1/x.png", null))
                .isInstanceOf(InvalidMediaException.class).hasMessageContaining("stored contentType");
        // formats we do not serve, polyglots and markup prefixes never sniff as an image
        for (String junk : new String[]{"GIF89a\u0001\u0000\u0001\u0000", "BM\u0000\u0000\u0000\u0000", "II*\u0000\u0008\u0000\u0000\u0000",
                "<svg xmlns='http://www.w3.org/2000/svg'/>", "<html><body>", "<?xml version='1.0'?><svg/>",
                "\n<html>\u0089PNG", " GIF89a"}) {
            refused("p/o/1/x.png", null, junk.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), null, null, "not an allowed image");
        }
        // markup AFTER a valid header is inert data to an image-only store: accepted by the header checks, served as image/* + nosniff
        byte[] withTrailingHtml = concat(TestImages.png(10, 10), "<html><script>alert(1)</script>".getBytes());
        ok("p/o/1/x.png", "image/png", withTrailingHtml, 10, 10);
    }

    @Test
    void the_size_bound_uses_the_stores_real_size_not_anything_declared() {
        byte[] png = TestImages.png(10, 10);
        assertThatThrownBy(() -> verifier(1_000_001, "image/png", png).verify("p/o/1/x.png", "image/png", 10, 10))
                .isInstanceOf(InvalidMediaException.class).hasMessageContaining("size");
        assertThatThrownBy(() -> verifier(0, "image/png", png).verify("p/o/1/x.png", "image/png", 10, 10)).isInstanceOf(InvalidMediaException.class);
        verifier(1_000_000, "image/png", png).verify("p/o/1/x.png", "image/png", 10, 10);
    }

    @Test
    void storage_less_environments_fail_closed_unless_local_test_or_dev() {
        MediaIngestVerifier closed = new MediaIngestVerifier(new DisabledMediaStorage(), POLICY, 50_000_000L, 20_000, false);
        assertThatThrownBy(closed::requireVerifiableOrAllowed).isInstanceOf(MediaIngestVerifier.MediaStorageNotConfiguredException.class);
        new MediaIngestVerifier(new DisabledMediaStorage(), POLICY, 50_000_000L, 20_000, true).requireVerifiableOrAllowed();
        new MediaIngestVerifier(storage(1, "image/png", TestImages.png(1, 1)), POLICY, 50_000_000L, 20_000, false).requireVerifiableOrAllowed();   // storage present: nothing to refuse

        MediaStorageConfig cfg = new MediaStorageConfig();
        for (String env : new String[]{"", "local", "test", "dev", " test "}) {
            cfg.mediaIngestVerifier(new DisabledMediaStorage(), POLICY, 1, 1, env).requireVerifiableOrAllowed();
        }
        for (String env : new String[]{"prod", "production", "staging", "Test", "DEV"}) {
            MediaIngestVerifier v = cfg.mediaIngestVerifier(new DisabledMediaStorage(), POLICY, 1, 1, env);
            assertThatThrownBy(v::requireVerifiableOrAllowed).as(env).isInstanceOf(MediaIngestVerifier.MediaStorageNotConfiguredException.class);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
