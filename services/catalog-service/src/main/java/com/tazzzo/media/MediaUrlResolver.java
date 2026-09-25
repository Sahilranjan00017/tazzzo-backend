package com.tazzzo.media;

import java.net.URI;

/**
 * Derives the public HTTPS URL for an asset key (STEP 6 decision B / STEP 18).
 *
 * <p>The database stores ONLY storage-neutral {@code assetKey}s; this resolver joins a
 * configuration-supplied HTTPS base with the key at read time. Changing the CDN host later is a
 * configuration change — zero data rewrites. No AWS SDK, no network call, no secrets, no bucket
 * internals in the result.
 *
 * <p><b>No fake CDN (STEP 7):</b> nothing here presumes {@code cdn.tazzzo.com} exists. The
 * production base URL is DEPLOYMENT CONFIGURATION and a prerequisite (CDN-HOST-1: DNS + TLS +
 * origin + verified asset are required BEFORE any client points at a CDN hostname). Until that
 * configuration is supplied, {@link #unconfigured()} fails safely — a typed error the moment
 * resolution is actually required, never a fabricated URL.
 */
public final class MediaUrlResolver {

    private final String baseUrl; // null = unconfigured (fail-safe on use)

    private MediaUrlResolver(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Build from deployment configuration.
     *
     * <p><b>Frozen base-URL policy (PR-05 review):</b> the base MUST be absolute HTTPS with a
     * host; an explicit port and a path prefix (e.g. {@code https://media.example.com/assets})
     * are SUPPORTED — valid CDN architecture is not over-restricted. REJECTED: userinfo
     * (embedded credentials would leak into every resolved public URL), query, fragment,
     * traversal in the path prefix, and every non-HTTPS scheme. Trailing slashes are normalised
     * away so joining is always {@code base + "/" + key}.
     */
    public static MediaUrlResolver of(String publicBaseUrl) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new InvalidMediaException("media public base URL required");
        }
        String trimmed = publicBaseUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new InvalidMediaException("malformed media base URL");
        }
        if (!"https".equals(uri.getScheme())) {
            // rejects http:, javascript:, data:, file:, everything non-HTTPS (STEP 19)
            throw new InvalidMediaException("media base URL must be https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidMediaException("media base URL must have a host");
        }
        if (uri.getRawUserInfo() != null) {
            // HIGH (PR-05 review): https://user:pass@host would embed credentials in every
            // resolved public URL. Rejected. The value is deliberately NOT logged or echoed.
            throw new InvalidMediaException("media base URL must not carry userinfo");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new InvalidMediaException("media base URL must not carry query/fragment");
        }
        // Validate the INTERIOR path prefix only: redundant trailing slashes are tolerated (they
        // are normalised away below), but traversal, empty interior segments, and percent
        // sequences in a configured prefix are config errors.
        String path = uri.getRawPath();
        if (path != null) {
            String interior = path.replaceAll("/+$", "");
            if (interior.contains("..") || interior.contains("//") || interior.contains("%")) {
                throw new InvalidMediaException("media base URL path prefix is unsafe");
            }
        }
        // normalise: exactly no trailing slash, so joining is always base + "/" + key
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return new MediaUrlResolver(trimmed);
    }

    /** The fail-safe placeholder until deployment supplies a real base (CDN is not provisioned). */
    public static MediaUrlResolver unconfigured() {
        return new MediaUrlResolver(null);
    }

    public boolean isConfigured() {
        return baseUrl != null;
    }

    /**
     * Deterministic join: {@code base + "/" + assetKey}. The key is re-validated here (defence in
     * depth — resolution may see keys from storage, not only from validated {@link MediaAsset}s).
     * All invalid inputs (including null) fail with the typed {@link InvalidMediaException} —
     * this component never leaks generic NPEs to callers.
     *
     * <p>Observability note: this is a pure config/value component and deliberately carries no
     * logger — failures surface as the typed exception, and the CALLER that invokes resolution
     * logs {@code media_url_resolution_failure} at its boundary.
     */
    public String resolve(String assetKey) {
        if (baseUrl == null) {
            throw new InvalidMediaException("media URL resolution unavailable: no public base configured");
        }
        if (assetKey == null || !MediaAsset.isSafeKey(assetKey)) {
            throw new InvalidMediaException("unsafe assetKey for URL resolution");
        }
        return baseUrl + "/" + assetKey;
    }
}
