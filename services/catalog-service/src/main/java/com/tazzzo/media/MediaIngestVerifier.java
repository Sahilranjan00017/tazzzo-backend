package com.tazzzo.media;

import java.util.Objects;

/**
 * Verifies a REFERENCED object against what storage really holds, closing the gap the {@link MediaAsset} model documents
 * (its {@code contentType} is only caller-asserted): the object must exist, be within the size ceiling, and its first
 * bytes must be the declared image type. Does nothing when no storage is configured (references stay unverified, which
 * the operator accepts by not configuring storage).
 */
public final class MediaIngestVerifier {

    private final MediaStorage storage;
    private final MediaUploadPolicy policy;
    private final MediaMetrics metrics;

    public MediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy) {
        this(storage, policy, MediaMetrics.unregistered());
    }

    public MediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy, MediaMetrics metrics) {
        this.storage = Objects.requireNonNull(storage);
        this.policy = Objects.requireNonNull(policy);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public boolean verifying() {
        return storage.enabled();
    }

    /**
     * @param declaredContentType the asset's claimed type; {@code null} means "whatever the bytes are, if allowed"
     * @throws InvalidMediaException missing object, oversize, or bytes that are not an allowed image of the declared type
     */
    public void verify(String assetKey, String declaredContentType) {
        if (!storage.enabled()) {
            return;
        }
        MediaMetrics.Verify outcome = MediaMetrics.Verify.OK;
        try {
            check(assetKey, declaredContentType);
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

    private void check(String assetKey, String declaredContentType) {
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
    }
}
