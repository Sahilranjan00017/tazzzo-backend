package com.tazzzo.catalog.api;

/**
 * Explicit classification of every HTTP surface (CAT-SEC-1 Q4-a / Q4-b / Q4-f).
 *
 * <p>The question is no longer "which URLs does the auth filter skip?" but "what IS this surface?"
 * — and the answer is one of exactly three things:
 * <pre>
 *   PUBLIC_CONSUMER   /consumer/v1, /consumer/v1/**        public BY DECISION (Q4-b)
 *   INTERNAL          /api, /api/**, the OpenAPI surface   service-token boundary
 *   UNKNOWN           everything else                      DENIED by default (Q4-f)
 * </pre>
 *
 * <p>Namespace matching is EXACT. {@code /consumer}, {@code /consumer/}, {@code /consumer/v1x},
 * {@code /consumer/v10}, {@code /consumer/v2/**} and {@code /consumer-public/**} are all UNKNOWN,
 * never public. A public namespace is a deliberate allowlist entry, not a prefix that happens to
 * match.
 *
 * <p>A request URI carrying a {@code ..} segment, or a percent-encoded dot or slash, is UNKNOWN.
 * The container maps such a request on its NORMALISED path while {@code getRequestURI()} reports
 * the raw one, so {@code /consumer/v1/../api/v1/products} would otherwise classify as public and
 * dispatch as internal. Refusing un-normalised paths closes that gap in the only safe direction.
 */
public final class SurfaceClassifier {

    public enum Surface { PUBLIC_CONSUMER, INTERNAL, UNKNOWN }

    private SurfaceClassifier() {
    }

    public static Surface classify(String uri) {
        if (uri == null || uri.isEmpty() || !isNormalised(uri)) {
            return Surface.UNKNOWN;
        }
        if (uri.equals("/consumer/v1") || uri.startsWith("/consumer/v1/")) {
            return Surface.PUBLIC_CONSUMER;
        }
        if (uri.equals("/api") || uri.startsWith("/api/") || isOpenApiSurface(uri)) {
            return Surface.INTERNAL;
        }
        return Surface.UNKNOWN;
    }

    /** /v3/api-docs, /v3/api-docs.yaml and every sub-path (groups, swagger-config) — Q4-e. */
    public static boolean isOpenApiSurface(String uri) {
        return uri.equals("/v3/api-docs") || uri.equals("/v3/api-docs.yaml")
                || uri.startsWith("/v3/api-docs/");
    }

    /**
     * True when the raw URI contains no traversal or encoded-structure sequences. Classification
     * is only meaningful on a path the container will map as written.
     */
    static boolean isNormalised(String uri) {
        String lower = uri.toLowerCase();
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("\\")) {
            return false;
        }
        for (String segment : uri.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                return false;
            }
        }
        return true;
    }
}
