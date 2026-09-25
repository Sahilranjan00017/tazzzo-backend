package com.tazzzo.catalog;

import com.tazzzo.catalog.ratelimit.InstallationIdResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Q5-INSTALL-1. An opaque rate-limit dimension — never authentication, never authorization. */
class InstallationIdResolverTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc123", "9f8e7d6c-1234-4567-89ab-cdef01234567",
            "install.id_v2:42", "A", "a-b_c.d:e"})
    void a_conservative_opaque_token_is_accepted(String value) {
        assertThat(InstallationIdResolver.resolve(value)).contains(value);
    }

    @Test
    void surrounding_whitespace_is_trimmed_so_one_install_is_one_bucket() {
        assertThat(InstallationIdResolver.resolve("  abc123  ")).contains("abc123");
    }

    /**
     * Malformed is IGNORED, not rejected. Rejecting the request would turn an anti-abuse hint into
     * a de facto credential; accepting the bytes would let a caller pick an arbitrary bucket key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "has space", "semi;colon", "new\nline", "quote\"", "sl/ash",
            "brace{}", "star*", "percent%", "hash#", "at@sign"})
    void a_malformed_value_is_ignored_rather_than_rejected(String value) {
        assertThat(InstallationIdResolver.resolve(value))
                .as("the dimension simply does not apply; the IP bucket still does")
                .isEmpty();
    }

    @Test
    void an_over_long_value_is_ignored() {
        assertThat(InstallationIdResolver.resolve("a".repeat(128))).isPresent();
        assertThat(InstallationIdResolver.resolve("a".repeat(129))).isEmpty();
    }

    @Test
    void an_absent_header_is_simply_absent() {
        assertThat(InstallationIdResolver.resolve(null)).isEmpty();
    }

    @Test
    void the_header_name_is_the_ratified_one() {
        assertThat(InstallationIdResolver.HEADER).isEqualTo("X-Tazzzo-Installation-Id");
    }
}
