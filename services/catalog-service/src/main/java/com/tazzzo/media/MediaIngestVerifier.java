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

    public MediaIngestVerifier(MediaStorage storage, MediaUploadPolicy policy) {
        this.storage = Objects.requireNonNull(storage);
        this.policy = Objects.requireNonNull(policy);
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
        StoredObject object = storage.inspect(assetKey)
                .orElseThrow(() -> new InvalidMediaException("asset not found in storage: upload it first"));
        if (object.sizeBytes() < 1 || object.sizeBytes() > policy.maxBytes()) {
            throw new InvalidMediaException("stored object size is outside 1.." + policy.maxBytes());
        }
        String sniffed = MediaSniffer.detect(object.head())
                .orElseThrow(() -> new InvalidMediaException("stored object is not an allowed image"));
        if (declaredContentType != null && !declaredContentType.equals(sniffed)) {
            throw new InvalidMediaException("declared contentType does not match the stored bytes");
        }
    }
}
