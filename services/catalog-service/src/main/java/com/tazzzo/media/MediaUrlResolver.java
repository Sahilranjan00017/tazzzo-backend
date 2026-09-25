package com.tazzzo.media;

import java.net.URI;
import java.util.Objects;

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

    /** Build from deployment configuration. The base MUST be absolute HTTPS with no query/fragment. */
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
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new InvalidMediaException("media base URL must not carry query/fragment");
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
     */
    public String resolve(String assetKey) {
        if (baseUrl == null) {
            // media_url_resolution_failure hook: typed, safe, no fabricated URL
            throw new InvalidMediaException("media URL resolution unavailable: no public base configured");
        }
        Objects.requireNonNull(assetKey, "assetKey required");
        // Reuse the asset-level shape rules by constructing a throwaway validated asset key check.
        if (assetKey.isBlank() || assetKey.length() > MediaAsset.MAX_ASSET_KEY
                || assetKey.startsWith("/") || assetKey.contains("..") || assetKey.contains("//")
                || !assetKey.matches("^[A-Za-z0-9][A-Za-z0-9/_.-]*$")) {
            throw new InvalidMediaException("unsafe assetKey for URL resolution");
        }
        return baseUrl + "/" + assetKey;
    }
}
