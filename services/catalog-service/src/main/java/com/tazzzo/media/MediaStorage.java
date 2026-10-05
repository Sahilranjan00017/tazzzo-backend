package com.tazzzo.media;

import java.util.Optional;

/**
 * The seam to object storage. The platform never proxies image bytes: an admin asks for a short-lived direct upload
 * target for ONE server-generated key, uploads straight to storage, and later references that key from a media set,
 * at which point the key is verified against what storage really holds ({@link #inspect}).
 *
 * <p>No concrete provider ships in this repository: choosing one (S3, GCS, ...) needs a business decision and a
 * credential held in the secret store, so the default is {@link DisabledMediaStorage}. A provider module contributes
 * its own bean; the default is {@code @ConditionalOnMissingBean}.
 */
public interface MediaStorage {

    /** False when no storage is configured: uploads are refused and media set writes stay metadata-only (unverified). */
    boolean enabled();

    /** An upload instruction for exactly {@code assetKey}, accepting {@code contentType} and at most {@code maxBytes}. */
    UploadTarget createUpload(String assetKey, String contentType, long maxBytes);

    /** The stored object (size, stored content type, first bytes), or empty when no such object exists. */
    Optional<StoredObject> inspect(String assetKey);
}
