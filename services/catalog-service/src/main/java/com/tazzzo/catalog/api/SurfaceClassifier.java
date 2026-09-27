package com.tazzzo.catalog.api;

/**
 * Explicit classification of every HTTP surface (CAT-SEC-1 Q4-a / Q4-b / Q4-f).
 *
 * <p>The question is no longer "which URLs does the auth filter skip?" but "what IS this surface?"
 * — and the answer is one of exactly four things:
 * <pre>
 *   PUBLIC_CONSUMER          /catalog/v1, /catalog/v1/**, /v1/auth/**,
 *                            /v1/** (except /v1/customer/**)         public BY DECISION (Q4-b)
 *   CUSTOMER_AUTHENTICATED   /v1/customer, /v1/customer/**           customer bearer boundary (PR-11A)
 *   INTERNAL                 /api, /api/**, the OpenAPI surface      service-token boundary
 *   UNKNOWN                  everything else                        DENIED by default (Q4-f)
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
 * <p><b>PR-11A — {@code /v1/customer/**} is carved OUT of the broad {@code /v1} public rule.</b>
 * The specific authenticated-namespace check runs BEFORE the generic {@code /v1} prefix match, so
 * {@code /v1/customer/profile} is {@code CUSTOMER_AUTHENTICATED}, never swallowed by the public
 * rule. Matching is the SAME exact-prefix discipline as every other namespace here: only
 * {@code /v1/customer} and {@code /v1/customer/**} qualify — {@code /v1/customers},
 * {@code /v1/customerx} and {@code /v1/customer-public} all fall through to the generic {@code /v1}
 * rule (PUBLIC_CONSUMER), never to CUSTOMER_AUTHENTICATED by loose prefix matching.
 * {@code /v1/auth/**} is reserved for the future OTP/login/refresh/logout endpoints (PR-11B/11C)
 * and stays PUBLIC_CONSUMER — those endpoints are how a client OBTAINS a customer token, so they
 * cannot themselves require one.
 *
 * <p>A request URI carrying a {@code ..} segment, or a percent-encoded dot or slash, is UNKNOWN.
 * The container maps such a request on its NORMALISED path while {@code getRequestURI()} reports
 * the raw one, so {@code /catalog/v1/../api/v1/products} would otherwise classify as public and
 * dispatch as internal. Refusing un-normalised paths closes that gap in the only safe direction —
 * and it applies equally to {@code /v1/customer/../api/...}, which is UNKNOWN, never authenticated.
 */
public final class SurfaceClassifier {

    public enum Surface { PUBLIC_CONSUMER, CUSTOMER_AUTHENTICATED, INTERNAL, UNKNOWN }

    private SurfaceClassifier() {
    }

    public static Surface classify(String uri) {
        if (uri == null || uri.isEmpty() || !isNormalised(uri)) {
            return Surface.UNKNOWN;
        }
        if (uri.equals("/catalog/v1") || uri.startsWith("/catalog/v1/")) {
            return Surface.PUBLIC_CONSUMER;
        }
        // PR-11A: the authenticated customer namespace MUST be checked before the generic /v1
        // public rule below, or /v1/customer/** would be swallowed by it.
        if (uri.equals("/v1/customer") || uri.startsWith("/v1/customer/")) {
            return Surface.CUSTOMER_AUTHENTICATED;
        }
        // PR-10B: the public commerce read surface. EXACT-prefix like /catalog/v1 — "/v1" and
        // "/v1/**" only, so "/v10", "/v1x", "/v2/**" stay UNKNOWN. Path-normalization guard above
        // still applies. Framework errors here flatten to the public envelope (ApiExceptionHandler).
        // This also covers /v1/auth/** (PR-11A reservation) since it is not carved out above.
        if (uri.equals("/v1") || uri.startsWith("/v1/")) {
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
