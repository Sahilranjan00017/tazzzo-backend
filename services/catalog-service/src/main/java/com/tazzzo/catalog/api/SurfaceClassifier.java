package com.tazzzo.catalog.api;

/**
 * Explicit classification of every HTTP surface (CAT-SEC-1 Q4-a / Q4-b / Q4-f).
 *
 * <p>The question is no longer "which URLs does the auth filter skip?" but "what IS this surface?"
 * — and the answer is one of exactly three things:
 * <pre>
 *   PUBLIC_CONSUMER   /catalog/v1, /catalog/v1/**         public BY DECISION (Q4-b)
 *   INTERNAL          /api, /api/**, the OpenAPI surface   service-token boundary
 *   UNKNOWN           everything else                      DENIED by default (Q4-f)
 * </pre>
 *
 * <p>Namespace matching is EXACT. {@code /catalog}, {@code /catalog/}, {@code /catalog/v1x},
 * {@code /catalog/v10}, {@code /catalog/v2/**} and {@code /catalog-public/**} are all UNKNOWN,
 * never public. A public namespace is a deliberate allowlist entry, not a prefix that happens to
 * match.
 *
 * <p><b>Phase 4B.1 (2026-09-09):</b> the public namespace moved from {@code /consumer/v1} to
 * {@code /catalog/v1}. The gateway performs NO rewrite — the ALB routes {@code /catalog/v1/**} to
 * this service, which receives that path verbatim, and the mobile client has been built against it
 * since before this service existed. {@code /consumer/v1/**} is now UNKNOWN like any other
 * unrecognised path. Q4-b's ratified invariant was a SEPARATE, EXPLICIT public namespace, not the
 * literal string, so this is a substitution rather than a re-ratification.
 *
 * <p>A request URI carrying a {@code ..} segment, or a percent-encoded dot or slash, is UNKNOWN.
 * The container maps such a request on its NORMALISED path while {@code getRequestURI()} reports
 * the raw one, so {@code /catalog/v1/../api/v1/products} would otherwise classify as public and
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
        if (uri.equals("/catalog/v1") || uri.startsWith("/catalog/v1/")) {
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
