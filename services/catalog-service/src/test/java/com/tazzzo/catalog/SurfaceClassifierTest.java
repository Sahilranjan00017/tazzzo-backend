package com.tazzzo.catalog;

import com.tazzzo.catalog.api.SurfaceClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.tazzzo.catalog.api.SurfaceClassifier.Surface.CUSTOMER_AUTHENTICATED;
import static com.tazzzo.catalog.api.SurfaceClassifier.Surface.INTERNAL;
import static com.tazzzo.catalog.api.SurfaceClassifier.Surface.PUBLIC_CONSUMER;
import static com.tazzzo.catalog.api.SurfaceClassifier.Surface.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure classifier test — no Spring, no HTTP. HTTP tests alone can miss a bad near-prefix branch;
 * this pins the exact namespace boundary.
 */
class SurfaceClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {"/catalog/v1", "/catalog/v1/", "/catalog/v1/taxonomy/root",
            "/catalog/v1/products/TZP-1", "/catalog/v1/categories", "/catalog/v1/does-not-exist"})
    void exact_catalog_v1_namespace_is_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri)).isEqualTo(PUBLIC_CONSUMER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1", "/v1/", "/v1/categories", "/v1/categories/TZS-000001/products",
            "/v1/products/TZP-1", "/v1/serviceability"})
    void exact_commerce_v1_namespace_is_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " is the PR-10B public commerce surface").isEqualTo(PUBLIC_CONSUMER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v10", "/v10/foo", "/v1x", "/v1x/foo", "/v2/foo", "/v", "/xv1/foo"})
    void near_miss_commerce_v1_namespaces_are_never_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " must not be public by /v1 prefix accident").isEqualTo(UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/catalog", "/catalog/", "/catalog/v1x", "/catalog/v1x/foo",
            "/catalog/v10", "/catalog/v10/foo", "/catalog/v2/foo", "/catalog-public/foo",
            "/catalogv1", "/Catalog/v1/foo", "/xcatalog/v1/foo"})
    void near_miss_catalog_namespaces_are_never_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " must not be public by prefix accident")
                .isEqualTo(UNKNOWN);
    }

    /**
     * Phase 4B.1 — the OLD public namespace carries no privilege whatsoever. It is not merely
     * "no longer preferred"; it is as unrecognised as any invented path, so a client still calling
     * it is refused rather than quietly served.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/consumer/v1", "/consumer/v1/", "/consumer/v1/taxonomy/root",
            "/consumer/v1/products/TZP-1", "/consumer", "/consumer/v2/foo"})
    void the_retired_consumer_namespace_is_now_unknown(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " was the pre-4B.1 public namespace and must now be UNKNOWN")
                .isEqualTo(UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/", "/api/v1/products", "/v3/api-docs",
            "/v3/api-docs.yaml", "/v3/api-docs/swagger-config"})
    void internal_surfaces(String uri) {
        assertThat(SurfaceClassifier.classify(uri)).isEqualTo(INTERNAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/definitely-not-a-surface", "/actuator/health", "/error",
            "/apix/v1", "/v3/api-docsx", "/v3", "/swagger-ui/index.html", "/catalog/v1x"})
    void everything_else_is_unknown(String uri) {
        assertThat(SurfaceClassifier.classify(uri)).isEqualTo(UNKNOWN);
    }

    /**
     * The container maps a request on its NORMALISED path while getRequestURI() reports the raw
     * one. A raw public prefix followed by ".." must not classify as public.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/catalog/v1/../api/v1/products", "/catalog/v1/./x",
            "/catalog/v1/%2e%2e/api/v1/products", "/catalog/v1/a%2Fb", "/catalog/v1/..",
            "/catalog/v1\\..\\api"})
    void un_normalised_paths_are_unknown_not_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri)).isEqualTo(UNKNOWN);
    }

    @Test
    void null_and_empty_are_unknown() {
        assertThat(SurfaceClassifier.classify(null)).isEqualTo(UNKNOWN);
        assertThat(SurfaceClassifier.classify("")).isEqualTo(UNKNOWN);
    }

    // ---------- PR-11A: /v1/auth/** is reserved PUBLIC (future OTP/login/refresh/logout) ----------

    @ParameterizedTest
    @ValueSource(strings = {"/v1/auth", "/v1/auth/", "/v1/auth/otp/request", "/v1/auth/otp/verify",
            "/v1/auth/refresh", "/v1/auth/logout"})
    void auth_namespace_is_reserved_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " is the reserved PR-11B/11C public auth namespace").isEqualTo(PUBLIC_CONSUMER);
    }

    // ---------- PR-11A: /v1/customer/** is CUSTOMER_AUTHENTICATED ----------

    @ParameterizedTest
    @ValueSource(strings = {"/v1/customer", "/v1/customer/", "/v1/customer/profile",
            "/v1/customer/addresses", "/v1/customer/addresses/1", "/v1/customer/anything/deep/nested"})
    void exact_customer_namespace_is_authenticated(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " is the PR-11A authenticated customer surface").isEqualTo(CUSTOMER_AUTHENTICATED);
    }

    /**
     * PR-11A — the authenticated prefix must be checked BEFORE the generic {@code /v1} public
     * rule, so these near-misses fall through to PUBLIC_CONSUMER (they are still under the broad
     * {@code /v1/**} umbrella) rather than being swallowed as authenticated by loose prefix
     * matching. None of these is a real endpoint; the point is the SURFACE, not the route.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/customers", "/v1/customerx", "/v1/customer-public",
            "/v1/customer-x/foo", "/v1/CUSTOMER", "/v1/Customer/profile"})
    void near_miss_customer_namespaces_are_never_authenticated_by_loose_prefix(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " must fall through to the generic /v1 public rule, never CUSTOMER_AUTHENTICATED")
                .isEqualTo(PUBLIC_CONSUMER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v10/customer", "/v1x/customer", "/v2/customer/profile"})
    void near_miss_customer_namespaces_under_a_near_miss_version_prefix_are_unknown(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " must not be public or authenticated by /v1 prefix accident")
                .isEqualTo(UNKNOWN);
    }

    /** Path traversal/normalization attacks against the authenticated namespace must be UNKNOWN. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/customer/../api/v1/products", "/v1/customer/../../api",
            "/v1/customer/%2e%2e/api", "/v1/customer/./profile", "/v1/customer/..",
            "/v1/customer/a%2Fb"})
    void customer_namespace_path_traversal_is_unknown_never_authenticated(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " must never classify as CUSTOMER_AUTHENTICATED or PUBLIC_CONSUMER")
                .isEqualTo(UNKNOWN);
    }

    /**
     * Explicit ordering proof: without the customer-prefix check running FIRST, the generic
     * {@code /v1} rule (which matches any {@code /v1/*}) would swallow this into PUBLIC_CONSUMER.
     */
    @Test
    void customer_profile_is_not_swallowed_by_the_generic_v1_public_rule() {
        assertThat(SurfaceClassifier.classify("/v1/customer/profile"))
                .as("must be CUSTOMER_AUTHENTICATED, not PUBLIC_CONSUMER")
                .isEqualTo(CUSTOMER_AUTHENTICATED)
                .isNotEqualTo(PUBLIC_CONSUMER);
    }
}
