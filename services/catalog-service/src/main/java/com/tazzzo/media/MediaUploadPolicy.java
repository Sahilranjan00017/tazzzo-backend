package com.tazzzo.media;

import java.util.Map;
import java.util.UUID;

/**
 * Server-side rules of an upload: the content-type allowlist with its file extension, the size ceiling, and the key
 * the SERVER generates. A client never chooses a key: it is {@code p/<ownerType>/<ownerId-safe>/<uuid>.<ext>} over the
 * closed key alphabet, so it cannot traverse, collide or overwrite another owner's object.
 */
public final class MediaUploadPolicy {

    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS =
            Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final long maxBytes;

    public MediaUploadPolicy(long maxBytes) {
        if (maxBytes < 1 || maxBytes > 50L * 1024 * 1024) {
            throw new IllegalArgumentException("max upload bytes must be between 1 and 52428800");
        }
        this.maxBytes = maxBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** @throws InvalidMediaException unsupported type or a size outside 1..max */
    public void requireAcceptable(String contentType, long sizeBytes) {
        if (contentType == null || !EXTENSIONS.containsKey(contentType)) {
            throw new InvalidMediaException("unsupported contentType");
        }
        if (sizeBytes < 1 || sizeBytes > maxBytes) {
            throw new InvalidMediaException("sizeBytes must be between 1 and " + maxBytes);
        }
    }

    /** The key namespace of one owner: every key issued for it starts with this, and only such keys may be referenced by it. */
    public String ownerPrefix(MediaOwnerType ownerType, String ownerId) {
        String safeOwner = ownerId.replaceAll("[^A-Za-z0-9_-]", "-");
        return "p/" + ownerType.name().toLowerCase(java.util.Locale.ROOT) + "/" + safeOwner + "/";
    }

    /** A fresh, safe object key for an owner. {@code ownerId} is reduced to the key alphabet (never trusted as-is). */
    public String newKey(MediaOwnerType ownerType, String ownerId, String contentType) {
        String ext = EXTENSIONS.get(contentType);
        if (ext == null) {
            throw new InvalidMediaException("unsupported contentType");
        }
        String key = ownerPrefix(ownerType, ownerId) + UUID.randomUUID() + "." + ext;
        if (!MediaAsset.isSafeKey(key)) {
            throw new InvalidMediaException("owner id cannot form a storage key");
        }
        return key;
    }
}
