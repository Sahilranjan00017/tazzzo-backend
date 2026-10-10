package com.tazzzo.media;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which verify outcome each refusal records, without storage or a database; and that the tag is only ever an outcome word. */
class MediaMetricsTest {

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final MediaUploadPolicy policy = new MediaUploadPolicy(100);

    MediaIngestVerifier verifier(MediaStorage s) {
        return new MediaIngestVerifier(s, policy, new MediaMetrics(registry));
    }

    static MediaStorage storage(java.util.function.Supplier<Optional<StoredObject>> inspect) {
        return new MediaStorage() {
            @Override public boolean enabled() { return true; }
            @Override public UploadTarget createUpload(String k, String t, long m) { throw new UnsupportedOperationException(); }
            @Override public Optional<StoredObject> inspect(String k) { return inspect.get(); }
        };
    }

    double verifyCount(String outcome) {
        var c = registry.find(MediaMetrics.VERIFY).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void each_refusal_records_its_own_outcome_and_still_throws_the_same_exception() {
        assertThatThrownBy(() -> verifier(storage(Optional::empty)).verify("k/x.jpg", null)).isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> verifier(storage(() -> Optional.of(new StoredObject(101, "image/jpeg", JPEG)))).verify("k/x.jpg", null))
                .isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> verifier(storage(() -> Optional.of(new StoredObject(0, "image/jpeg", JPEG)))).verify("k/x.jpg", null))
                .isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> verifier(storage(() -> Optional.of(new StoredObject(10, "image/jpeg", "<svg/>".getBytes())))).verify("k/x.jpg", null))
                .isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> verifier(storage(() -> Optional.of(new StoredObject(10, "image/jpeg", PNG)))).verify("k/x.jpg", "image/jpeg"))
                .as("declared jpeg, bytes png").isInstanceOf(InvalidMediaException.class);
        assertThatThrownBy(() -> verifier(storage(() -> Optional.of(new StoredObject(10, "image/png", JPEG)))).verify("k/x.jpg", null))
                .as("stored as png, bytes jpeg").isInstanceOf(InvalidMediaException.class);
        verifier(storage(() -> Optional.of(new StoredObject(10, "image/jpeg", JPEG)))).verify("k/x.jpg", "image/jpeg");

        assertThat(verifyCount("missing_object")).isEqualTo(1);
        assertThat(verifyCount("size_mismatch")).isEqualTo(2);
        assertThat(verifyCount("sniff_reject")).isEqualTo(1);
        assertThat(verifyCount("type_mismatch")).isEqualTo(2);
        assertThat(verifyCount("ok")).isEqualTo(1);
        assertThat(verifyCount("storage_error")).isZero();
    }

    @Test
    void a_storage_outage_is_its_own_outcome_and_propagates() {
        assertThatThrownBy(() -> verifier(storage(() -> { throw new MediaStorageFailure("S3Exception"); })).verify("k/x.jpg", null))
                .isInstanceOf(MediaStorageFailure.class);
        assertThatThrownBy(() -> verifier(storage(() -> { throw new IllegalStateException("boom"); })).verify("k/x.jpg", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(verifyCount("storage_error")).isEqualTo(2);
        assertThat(verifyCount("ok")).as("a failure is never also counted ok").isZero();
    }

    @Test
    void nothing_is_counted_when_no_storage_is_configured() {
        new MediaIngestVerifier(new DisabledMediaStorage(), policy, new MediaMetrics(registry)).verify("k/x.jpg", "image/png");
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void the_only_tag_is_outcome_with_a_lower_case_enum_word_even_for_hostile_keys() {
        MediaMetrics m = new MediaMetrics(registry);
        for (MediaMetrics.Presign o : MediaMetrics.Presign.values()) m.presign(o);
        for (MediaMetrics.Verify o : MediaMetrics.Verify.values()) m.verify(o);
        for (MediaMetrics.Write o : MediaMetrics.Write.values()) m.write(o);
        assertThatThrownBy(() -> verifier(storage(Optional::empty)).verify("p/product/TZP-123/../../etc/passwd", "image/png"))
                .isInstanceOf(InvalidMediaException.class);
        for (Meter meter : registry.getMeters()) {
            for (Tag t : meter.getId().getTags()) {
                assertThat(MediaMetrics.ALLOWED_TAG_KEYS).contains(t.getKey());
                assertThat(t.getValue()).matches("[a-z_]{2,20}");
            }
        }
    }
}
