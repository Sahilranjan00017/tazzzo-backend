package com.tazzzo.media;

import java.util.Objects;

/**
 * Verifies a REFERENCED object against what storage really holds, closing the gap the {@link MediaAsset} model documents
 * (its {@code contentType} is only caller-asserted): the object must exist, be within the size ceiling (the store's real
 * size), its first bytes must be the declared image type, the key extension must agree, and the header-only dimensions
 * must be within the pixel bounds and equal any declared width/height. Does nothing when no storage is configured; whether
 * that is acceptable is {@link #requireVerifiableOrAllowed()} (fail closed outside local/test/dev).
 */
public final class MediaIngestVerifier {

    private final MediaStorage storage;
    private final MediaUploadPolicy policy;
    private final MediaMetrics metrics;
    private final long maxPixels;
    private final int maxDimension;
    private final boolean unverifiedAllowed;

    /** Local/test/dev defaults: unverified references allowed, 50 MP and 20 000 px ceilings. */
    public MediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy) {
        this(storage, policy, DEFAULT_MAX_PIXELS, MediaAsset.MAX_DIMENSION, true, MediaMetrics.unregistered());
    }

    /** Local/test/dev defaults with metrics. */
    public MediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy, MediaMetrics metrics) {
        this(storage, policy, DEFAULT_MAX_PIXELS, MediaAsset.MAX_DIMENSION, true, metrics);
    }

    public MediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy, long maxPixels, int maxDimension,
                               boolean unverifiedAllowed) {
        this(storage, policy, maxPixels, maxDimension, unverifiedAllowed, MediaMetrics.unregistered());
    }

    public MediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy, long maxPixels, int maxDimension,
                               boolean unverifiedAllowed, MediaMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        this.storage = Objects.requireNonNull(storage);
        this.policy = Objects.requireNonNull(policy);
        if (maxPixels < 1 || maxDimension < 1) {
            throw new IllegalArgumentException("max pixels and max dimension must be positive");
        }
        this.maxPixels = maxPixels;
        this.maxDimension = maxDimension;
        this.unverifiedAllowed = unverifiedAllowed;
    }

    public static final long DEFAULT_MAX_PIXELS = 50_000_000L;

    /**
     * With no storage configured, references cannot be verified. Outside local/test/dev that is refused (fail closed),
     * never accepted silently.
     *
     * @throws MediaStorageNotConfiguredException storage is disabled and this environment does not allow unverified references
     */
    public void requireVerifiableOrAllowed() {
        if (!storage.enabled() && !unverifiedAllowed) {
            throw new MediaStorageNotConfiguredException();
        }
    }

    /** Unverified media references are refused in this environment because no storage is configured. */
    public static final class MediaStorageNotConfiguredException extends RuntimeException {
        public MediaStorageNotConfiguredException() {
            super("media storage is not configured: new media references cannot be verified");
        }
    }

    public boolean verifying() {
        return storage.enabled();
    }

    /**
     * @param declaredContentType the asset's claimed type; {@code null} means "whatever the bytes are, if allowed"
     * @throws InvalidMediaException missing object, oversize, or bytes that are not an allowed image of the declared type
     */
    public void verify(String assetKey, String declaredContentType) {
        verify(assetKey, declaredContentType, null, null);
    }

    /**
     * @param declaredWidth  the asset's claimed pixel width, or {@code null}
     * @param declaredHeight the asset's claimed pixel height, or {@code null}
     */
    public void verify(String assetKey, String declaredContentType, Integer declaredWidth, Integer declaredHeight) {
        if (!storage.enabled()) {
            return;
        }
        MediaMetrics.Verify outcome = MediaMetrics.Verify.OK;
        try {
            check(assetKey, declaredContentType, declaredWidth, declaredHeight);
        } catch (Rejected e) {
            outcome = e.outcome;
            throw e;
        } catch (RuntimeException e) {   // the store could not be asked (MediaStorageFailure) or failed unexpectedly
            outcome = MediaMetrics.Verify.STORAGE_ERROR;
            throw e;
        } finally {
            metrics.verify(outcome);
        }
    }

    /** The same {@link InvalidMediaException} callers have always received, carrying which check refused (for the metric only). */
    private static final class Rejected extends InvalidMediaException {
        final transient MediaMetrics.Verify outcome;

        Rejected(MediaMetrics.Verify outcome, String message) {
            super(message);
            this.outcome = outcome;
        }
    }

    private void check(String assetKey, String declaredContentType, Integer declaredWidth, Integer declaredHeight) {
        StoredObject object = storage.inspect(assetKey)
                .orElseThrow(() -> new Rejected(MediaMetrics.Verify.MISSING_OBJECT, "asset not found in storage: upload it first"));
        if (object.sizeBytes() < 1 || object.sizeBytes() > policy.maxBytes()) {
            throw new Rejected(MediaMetrics.Verify.SIZE_MISMATCH, "stored object size is outside 1.." + policy.maxBytes());
        }
        String sniffed = MediaSniffer.detect(object.head())
                .orElseThrow(() -> new Rejected(MediaMetrics.Verify.SNIFF_REJECT, "stored object is not an allowed image"));
        if (declaredContentType != null && !declaredContentType.equals(sniffed)) {
            throw new Rejected(MediaMetrics.Verify.TYPE_MISMATCH, "declared contentType does not match the stored bytes");
        }
        // the store serves the type it holds: bytes of one image type stored as another would be delivered mislabelled
        String stored = object.contentType() == null ? null
                : object.contentType().split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        if (stored != null && !stored.equals(sniffed)) {
            throw new Rejected(MediaMetrics.Verify.TYPE_MISMATCH, "stored contentType does not match the stored bytes");
        }
        // the key's extension is the third witness: a name that says one type over bytes of another is never referenced
        if (!policy.extensionMatches(assetKey, sniffed)) {
            throw new Rejected(MediaMetrics.Verify.EXTENSION_MISMATCH, "asset key extension does not match the stored bytes");
        }
        ImageHeader.Dimensions dims;
        try {
            dims = ImageHeader.read(sniffed, object.head());
        } catch (Rejected e) {
            throw e;
        } catch (InvalidMediaException e) {
            throw new Rejected(MediaMetrics.Verify.SNIFF_REJECT, e.getMessage());   // the header is not a readable image header
        }
        if (dims.width() > maxDimension || dims.height() > maxDimension) {
            throw new Rejected(MediaMetrics.Verify.DIMENSION_REJECT, "image dimensions exceed the maximum of " + maxDimension + " px per side");
        }
        if (dims.pixels() > maxPixels) {
            throw new Rejected(MediaMetrics.Verify.PIXEL_REJECT, "image has more than the maximum of " + maxPixels + " pixels");
        }
        if (declaredWidth != null && declaredWidth.longValue() != dims.width()
                || declaredHeight != null && declaredHeight.longValue() != dims.height()) {
            throw new Rejected(MediaMetrics.Verify.DIMENSION_MISMATCH, "declared width/height do not match the stored image");
        }
    }
}
