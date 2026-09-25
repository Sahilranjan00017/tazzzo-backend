package com.tazzzo.media;

import com.tazzzo.commerce.contract.ImageRole;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One image reference inside a {@link MediaSet}.
 *
 * <p><b>Identity is the {@code assetId}/{@code assetKey}, never a URL (STEP 8).</b>
 * {@code assetKey} is a storage-neutral object key (e.g. {@code p/TZP-100002/front.webp});
 * the public URL is DERIVED at read time by {@link MediaUrlResolver}, so a CDN domain change,
 * resizing pipeline, signing, or cache-busting never rewrites a media record.
 *
 * <p>Validation is deliberately strict because the repository is public and these values feed
 * public URLs later: keys are a closed character set with no traversal, alt text is bounded
 * plain text (no HTML), content types are an explicit allowlist, and dimensions come in pairs
 * (an aspect ratio needs both — one-sided dimensions are rejected rather than silently kept).
 */
public record MediaAsset(
        String assetId,
        String assetKey,
        ImageRole role,
        int sortOrder,
        String altText,
        Integer width,
        Integer height,
        String contentType
) {
    /** Closed world of image types accepted today (STEP 10). Extend deliberately, never infer. */
    public static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    static final int MAX_ALT_TEXT = 300;
    static final int MAX_ASSET_KEY = 512;
    /** Safe object-key alphabet: no leading slash, no traversal, no schemes, no spaces. */
    private static final Pattern KEY_SHAPE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_.-]*$");

    public MediaAsset {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId required");
        }
        if (assetKey == null || assetKey.isBlank() || assetKey.length() > MAX_ASSET_KEY) {
            throw new IllegalArgumentException("assetKey required (max " + MAX_ASSET_KEY + " chars)");
        }
        if (!KEY_SHAPE.matcher(assetKey).matches() || assetKey.contains("..") || assetKey.contains("//")) {
            throw new IllegalArgumentException("unsafe assetKey: " + assetKey);
        }
        Objects.requireNonNull(role, "role required");
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must be >= 0: " + sortOrder);
        }
        if (altText != null) {
            altText = altText.trim();
            if (altText.length() > MAX_ALT_TEXT) {
                throw new IllegalArgumentException("altText exceeds " + MAX_ALT_TEXT + " chars");
            }
            if (altText.contains("<") || altText.contains(">")) {
                throw new IllegalArgumentException("altText must be plain text (no HTML)");
            }
            if (altText.isEmpty()) altText = null;
        }
        // Dimensions come in pairs: aspect-ratio math needs both (STEP 10).
        if ((width == null) != (height == null)) {
            throw new IllegalArgumentException("width and height must both be present or both absent");
        }
        if (width != null && (width <= 0 || height <= 0)) {
            throw new IllegalArgumentException("dimensions must be positive: " + width + "x" + height);
        }
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported contentType: " + contentType);
        }
    }
}
