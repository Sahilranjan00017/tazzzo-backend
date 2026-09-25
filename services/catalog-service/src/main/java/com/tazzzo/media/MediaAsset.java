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
    /**
     * Closed world of image types accepted today (STEP 10). Extend deliberately, never infer.
     *
     * <p><b>CLAIMED metadata only:</b> no upload/ingestion exists yet, so {@code contentType}
     * is caller-asserted and is NOT verified against actual file bytes. The future ingestion
     * pipeline MUST inspect real content (magic bytes/MIME sniffing) before accepting storage —
     * this model provides no such guarantee and must never be claimed to.
     */
    public static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    static final int MAX_ALT_TEXT = 300;
    static final int MAX_ASSET_KEY = 512;
    static final int MAX_ASSET_ID = 128;
    /** Technical ceiling for one dimension — corrupt-metadata guard, not a business rule. */
    static final int MAX_DIMENSION = 20_000;
    /** Safe object-key alphabet: no leading slash, no traversal, no schemes, no spaces. */
    private static final Pattern KEY_SHAPE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_.-]*$");

    /**
     * The single key-safety rule, shared with {@link MediaUrlResolver} (defence in depth).
     *
     * <p><b>Why traversal is impossible under these rules:</b> the alphabet is closed ASCII
     * (letters, digits, {@code / _ . -}) — no {@code %} so percent-encoded traversal cannot be
     * smuggled and no decoding step exists to reintroduce it; no backslash, colon, space, or
     * non-ASCII lookalikes; {@code ..} is banned as a substring (which also bans {@code ...});
     * single-dot segments ({@code x/./y}, trailing {@code /.}) are banned; leading slash,
     * trailing slash, and empty segments ({@code //}) are banned. Every segment is therefore a
     * non-empty literal name that cannot navigate upward or re-root the path.
     */
    static boolean isSafeKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_ASSET_KEY) return false;
        if (!KEY_SHAPE.matcher(key).matches()) return false; // also excludes leading '/' and '.'
        if (key.contains("..") || key.contains("//") || key.endsWith("/")) return false;
        for (String segment : key.split("/")) {
            if (segment.isEmpty() || segment.chars().allMatch(ch -> ch == '.')) return false;
        }
        return true;
    }

    private static boolean hasControlChars(String s) {
        return s.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F);
    }

    public MediaAsset {
        if (assetId == null || assetId.isBlank() || assetId.length() > MAX_ASSET_ID
                || hasControlChars(assetId) || !assetId.equals(assetId.trim())) {
            throw new IllegalArgumentException(
                    "assetId required: non-blank, trimmed, no control chars, max " + MAX_ASSET_ID);
        }
        if (!isSafeKey(assetKey)) {
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
            // Plain-text storage rule: no angle brackets, no control characters. This is a DATA
            // hygiene rule, not XSS protection — output escaping remains the rendering
            // boundary's responsibility and is never satisfied by this check alone.
            if (altText.contains("<") || altText.contains(">") || hasControlChars(altText)) {
                throw new IllegalArgumentException("altText must be plain text (no HTML/control chars)");
            }
            if (altText.isEmpty()) altText = null;
        }
        // Dimensions come in pairs: aspect-ratio math needs both (STEP 10).
        if ((width == null) != (height == null)) {
            throw new IllegalArgumentException("width and height must both be present or both absent");
        }
        if (width != null && (width <= 0 || height <= 0
                || width > MAX_DIMENSION || height > MAX_DIMENSION)) {
            throw new IllegalArgumentException("dimensions must be in 1.." + MAX_DIMENSION
                    + ": " + width + "x" + height);
        }
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("unsupported contentType: " + contentType);
        }
    }
}
