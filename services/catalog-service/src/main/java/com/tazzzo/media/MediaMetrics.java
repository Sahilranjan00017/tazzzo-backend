package com.tazzzo.media;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Outcome counters for the media admin flow. The only tag is {@code outcome}, and its value is always a constant of one of
 * the enums below: an owner id, asset key, content type string or error message cannot reach a tag. Recording never throws
 * into the request it observes; a component built without a registry records into a private in-memory one.
 * <ul>
 *   <li>{@code media_upload_presign{outcome}}: a direct-to-storage upload target was requested.</li>
 *   <li>{@code media_verify{outcome}}: a newly referenced asset key was checked against what storage holds.</li>
 *   <li>{@code media_write{outcome}}: a whole-set media write (the {@code media_write_*} log sites).</li>
 * </ul>
 */
public class MediaMetrics {

    private static final Logger log = LoggerFactory.getLogger(MediaMetrics.class);

    public static final String PRESIGN = "media_upload_presign";
    public static final String VERIFY = "media_verify";
    public static final String WRITE = "media_write";
    public static final Set<String> ALLOWED_TAG_KEYS = Set.of("outcome");

    public enum Presign { OK, REJECTED, OWNER_NOT_FOUND, NOT_CONFIGURED, STORAGE_ERROR, ERROR }

    public enum Verify { OK, MISSING_OBJECT, SIZE_MISMATCH, SNIFF_REJECT, TYPE_MISMATCH, KEY_NOT_ISSUED, STORAGE_ERROR }

    public enum Write { SUCCESS, VALIDATION_FAILURE, CONFLICT, NOT_FOUND, ERROR }

    private final MeterRegistry registry;

    public MediaMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public static MediaMetrics unregistered() {
        return new MediaMetrics(new SimpleMeterRegistry());
    }

    public void presign(Presign outcome) {
        count(PRESIGN, "upload target requests, by outcome", outcome);
    }

    public void verify(Verify outcome) {
        count(VERIFY, "newly referenced asset keys checked against storage, by outcome", outcome);
    }

    public void write(Write outcome) {
        count(WRITE, "media set writes, by outcome", outcome);
    }

    private void count(String name, String description, Enum<?> outcome) {
        try {
            Counter.builder(name).description(description).tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry).increment();
        } catch (RuntimeException e) {
            log.warn("media metric recording failed and was ignored: {}", e.getClass().getSimpleName());
        }
    }
}
