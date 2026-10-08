package com.tazzzo.media;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaIngestUnitTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @Test
    void the_sniffer_recognises_exactly_the_allowed_images_from_their_magic_bytes() {
        assertThat(MediaSniffer.detect(JPEG)).contains("image/jpeg");
        assertThat(MediaSniffer.detect(PNG)).contains("image/png");
        assertThat(MediaSniffer.detect(WEBP)).contains("image/webp");
        assertThat(MediaSniffer.detect("GIF89a-not-allowed".getBytes())).isEmpty();
        assertThat(MediaSniffer.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes())).isEmpty();
        assertThat(MediaSniffer.detect("<html><script>alert(1)</script>".getBytes())).isEmpty();
        assertThat(MediaSniffer.detect(new byte[]{(byte) 0xFF, (byte) 0xD8})).as("too short").isEmpty();
        assertThat(MediaSniffer.detect(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'})).as("RIFF but not WEBP").isEmpty();
        assertThat(MediaSniffer.detect(new byte[0])).isEmpty();
        assertThat(MediaSniffer.detect(null)).isEmpty();
    }

    @Test
    void the_policy_allows_only_listed_types_and_bounded_sizes_and_generates_safe_keys() {
        MediaUploadPolicy p = new MediaUploadPolicy(1000);
        p.requireAcceptable("image/png", 1000);
        for (Object[] bad : new Object[][]{{"image/gif", 10L}, {null, 10L}, {"image/png", 0L}, {"image/png", 1001L}, {"text/html", 10L}}) {
            assertThatThrownBy(() -> p.requireAcceptable((String) bad[0], (Long) bad[1])).isInstanceOf(InvalidMediaException.class);
        }
        String key = p.newKey(MediaOwnerType.PRODUCT, "TZP-1", "image/webp");
        assertThat(key).matches("p/product/TZP-1/[0-9a-f-]{36}\\.webp");
        assertThat(MediaAsset.isSafeKey(key)).isTrue();
        String hostile = p.newKey(MediaOwnerType.SKU, "../../etc/passwd %00 x", "image/jpeg");
        assertThat(MediaAsset.isSafeKey(hostile)).as(hostile).isTrue();
        assertThat(hostile).doesNotContain("..").doesNotContain("%").doesNotContain(" ");
        assertThat(p.newKey(MediaOwnerType.SKU, "A", "image/png")).isNotEqualTo(p.newKey(MediaOwnerType.SKU, "A", "image/png"));
        assertThatThrownBy(() -> new MediaUploadPolicy(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaUploadPolicy(60L * 1024 * 1024)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MediaStorage storage(Optional<StoredObject> object) {
        return new MediaStorage() {
            @Override public boolean enabled() { return true; }
            @Override public UploadTarget createUpload(String assetKey, String contentType, long maxBytes) { throw new UnsupportedOperationException(); }
            @Override public Optional<StoredObject> inspect(String assetKey) { return object; }
        };
    }

    @Test
    void the_verifier_requires_existence_size_and_matching_bytes_and_is_a_no_op_without_storage() {
        MediaUploadPolicy policy = new MediaUploadPolicy(100);
        new MediaIngestVerifier(new DisabledMediaStorage(), policy).verify("p/x/y.jpg", "image/jpeg");   // no storage: nothing checked
        MediaIngestVerifier ok = new MediaIngestVerifier(storage(Optional.of(new StoredObject(50, "image/jpeg", JPEG))), policy);
        ok.verify("p/x/y.jpg", "image/jpeg");
        ok.verify("p/x/y.jpg", null);
        assertThatThrownBy(() -> ok.verify("p/x/y.png", "image/png")).as("declared png, bytes are jpeg").isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> new MediaIngestVerifier(storage(Optional.empty()), policy).verify("k/x.jpg", null))
                .isInstanceOf(InvalidMediaException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> new MediaIngestVerifier(storage(Optional.of(new StoredObject(101, "image/jpeg", JPEG))), policy).verify("k/x.jpg", null))
                .isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> new MediaIngestVerifier(storage(Optional.of(new StoredObject(0, "image/jpeg", JPEG))), policy).verify("k/x.jpg", null))
                .isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> new MediaIngestVerifier(storage(Optional.of(new StoredObject(10, "image/jpeg", "<svg/>".getBytes()))), policy).verify("k/x.jpg", null))
                .isInstanceOf(InvalidMediaException.class).hasMessageContaining("not an allowed image");
    }

    @Test
    void the_verifier_refuses_bytes_stored_under_another_image_type_even_when_no_type_is_declared() {
        MediaUploadPolicy policy = new MediaUploadPolicy(100);
        // jpeg bytes the store would serve as image/png: delivered mislabelled, so refused
        assertThatThrownBy(() -> new MediaIngestVerifier(storage(Optional.of(new StoredObject(50, "image/png", JPEG))), policy).verify("p/x/y.png", null))
                .isInstanceOf(InvalidMediaException.class).hasMessageContaining("stored contentType");
        // parameters and case are not a mismatch; a store that reports no type is judged on the bytes alone
        new MediaIngestVerifier(storage(Optional.of(new StoredObject(50, "IMAGE/JPEG; charset=binary", JPEG))), policy).verify("p/x/y.jpg", null);
        new MediaIngestVerifier(storage(Optional.of(new StoredObject(50, null, JPEG))), policy).verify("p/x/y.jpg", null);
    }
}
