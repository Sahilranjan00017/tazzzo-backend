package com.tazzzo.catalog.api;

/**
 * Explicit classification of every HTTP surface (CAT-SEC-1 Q4-a / Q4-b / Q4-f).
 *
 * <p>The question is no longer "which URLs does the auth filter skip?" but "what IS this surface?"
 * — and the answer is one of exactly four things:
 * <pre>
 *   PUBLIC_CONSUMER          /catalog/v1, /catalog/v1/**,
 *                            /v1/categories, /v1/categories/**,
 *                            /v1/products, /v1/products/**,
 *                            /v1/serviceability (exact only),
 *                            /v1/auth, /v1/auth/**              public BY DECISION, per-family (Q4-b)
 *   CUSTOMER_AUTHENTICATED   /v1/customer, /v1/customer/**           customer bearer boundary (PR-11A)
 *   INTERNAL                 /api, /api/**, the OpenAPI surface      service-token boundary
 *   UNKNOWN                  everything else, INCLUDING any other  DENIED by default (Q4-f)
 *                            /v1/** path not named above
 * </pre>
 *
 * <p>Namespace matching is EXACT, per named route family. {@code /catalog}, {@code /catalog/},
 * {@code /catalog/v1x}, {@code /catalog/v10}, {@code /catalog/v2/**} and {@code /catalog-public/**}
 * are all UNKNOWN, never public. A public namespace is a deliberate allowlist entry, not a prefix
 * that happens to match.
 *
 * <p><b>Phase 4B.1 (2026-09-09):</b> the public namespace moved from {@code /consumer/v1} to
 * {@code /catalog/v1}. The gateway performs NO rewrite — the ALB routes {@code /catalog/v1/**} to
 * this service, which receives that path verbatim, and the mobile client has been built against it
 * since before this service existed. {@code /consumer/v1/**} is now UNKNOWN like any other
 * unrecognised path. Q4-b's ratified invariant was a SEPARATE, EXPLICIT public namespace, not the
 * literal string, so this is a substitution rather than a re-ratification.
 *
 * <p><b>PR-11A hardening (deny-by-default under {@code /v1}).</b> Earlier PR-11A revisions treated
 * the entire {@code /v1} prefix as public (minus the carved-out customer namespace). That is unsafe
 * for the next phases of this service: a future engineer who adds {@code /v1/cart},
 * {@code /v1/orders}, {@code /v1/checkout} or {@code /v1/admin-test} without first touching this
 * class would have shipped it PUBLIC by accident. The blanket {@code /v1/**} rule is gone. Every
 * {@code /v1} route family that is actually public is named explicitly above; anything else under
 * {@code /v1} — including the bare {@code /v1} root itself — is UNKNOWN until a future PR
 * deliberately ratifies it here. This is the "NEW ROUTE != AUTOMATICALLY PUBLIC" invariant: a route
 * must be consciously classified, never inherited from a prefix.
 *
 * <p>The authenticated customer namespace ({@code /v1/customer}, {@code /v1/customer/**}) is
 * checked FIRST, before any public route family, purely for defensive clarity — with the blanket
 * rule removed there is no longer any public family it could be swallowed by, but keeping the
 * ordering explicit means a future family addition can never silently re-introduce that hazard.
 * Matching is the SAME exact-prefix discipline as every other namespace here: only
 * {@code /v1/customer} and {@code /v1/customer/**} qualify — {@code /v1/customers},
 * {@code /v1/customerx} and {@code /v1/customer-public} are near-misses and are now UNKNOWN (they
 * are not a named public family either), never CUSTOMER_AUTHENTICATED by loose prefix matching.
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
        // Checked before every named public /v1 family — see class javadoc.
        if (uri.equals("/v1/customer") || uri.startsWith("/v1/customer/")) {
            return Surface.CUSTOMER_AUTHENTICATED;
        }
        if (isPublicCommerceV1(uri)) {
            return Surface.PUBLIC_CONSUMER;
        }
        if (uri.equals("/api") || uri.startsWith("/api/") || isOpenApiSurface(uri)) {
            return Surface.INTERNAL;
        }
        return Surface.UNKNOWN;
    }

    /**
     * PR-11A hardening — the explicit, ratified {@code /v1} public route-family allowlist. Every
     * entry mirrors an actual mapping in {@code CommerceReadController} (or the reserved future auth
     * namespace); nothing here is a bare {@code /v1} prefix match. Adding a new public {@code /v1}
     * route means adding a deliberate entry here — never widening an existing one to a broader
     * prefix.
     */
    private static boolean isPublicCommerceV1(String uri) {
        return uri.equals("/v1/categories") || uri.startsWith("/v1/categories/")
                || uri.equals("/v1/products") || uri.startsWith("/v1/products/")
                || uri.equals("/v1/serviceability")
                || uri.equals("/v1/auth") || uri.startsWith("/v1/auth/");
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
