package com.tazzzo.catalog;

import com.tazzzo.catalog.api.SurfaceClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    @ValueSource(strings = {"/consumer/v1", "/consumer/v1/", "/consumer/v1/taxonomy/root",
            "/consumer/v1/products/TZP-1", "/consumer/v1/does-not-exist"})
    void exact_consumer_v1_namespace_is_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri)).isEqualTo(PUBLIC_CONSUMER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/consumer", "/consumer/", "/consumer/v1x", "/consumer/v1x/foo",
            "/consumer/v10", "/consumer/v10/foo", "/consumer/v2/foo", "/consumer-public/foo",
            "/consumerv1", "/Consumer/v1/foo", "/xconsumer/v1/foo"})
    void near_miss_consumer_namespaces_are_never_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri))
                .as(uri + " must not be public by prefix accident")
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
            "/apix/v1", "/v3/api-docsx", "/v3", "/swagger-ui/index.html"})
    void everything_else_is_unknown(String uri) {
        assertThat(SurfaceClassifier.classify(uri)).isEqualTo(UNKNOWN);
    }

    /**
     * The container maps a request on its NORMALISED path while getRequestURI() reports the raw
     * one. A raw public prefix followed by ".." must not classify as public.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/consumer/v1/../api/v1/products", "/consumer/v1/./x",
            "/consumer/v1/%2e%2e/api/v1/products", "/consumer/v1/a%2Fb", "/consumer/v1/..",
            "/consumer/v1\\..\\api"})
    void un_normalised_paths_are_unknown_not_public(String uri) {
        assertThat(SurfaceClassifier.classify(uri)).isEqualTo(UNKNOWN);
    }

    @Test
    void null_and_empty_are_unknown() {
        assertThat(SurfaceClassifier.classify(null)).isEqualTo(UNKNOWN);
        assertThat(SurfaceClassifier.classify("")).isEqualTo(UNKNOWN);
    }
}
