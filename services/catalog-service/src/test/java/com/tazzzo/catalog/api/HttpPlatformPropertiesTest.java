package com.tazzzo.catalog.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpPlatformPropertiesTest {

    private static HttpPlatformProperties withOrigins(String... origins) {
        HttpPlatformProperties p = new HttpPlatformProperties();
        p.getCors().setAllowedOrigins(List.of(origins));
        return p;
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://cms.tazzzo.com", "https://cms.tazzzo.com:8443", "http://localhost:3000", "http://127.0.0.1:5173", "HTTPS://CMS.TAZZZO.COM"})
    void exact_origins_are_accepted(String origin) {
        assertThatCode(() -> withOrigins(origin).validate()).doesNotThrowAnyException();
        assertThat(withOrigins(origin).getCors().enabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.tazzzo.com", "http://cms.tazzzo.com", "cms.tazzzo.com", "https://cms.tazzzo.com/",
            "https://cms.tazzzo.com/path", "https://user:pw@cms.tazzzo.com", "https://cms.tazzzo.com?x=1", "null", "file://x"})
    void anything_that_is_not_an_exact_https_origin_fails_startup(String origin) {
        assertThatThrownBy(() -> withOrigins(origin).validate()).as(origin).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-origins");
    }

    @Test
    void cors_is_off_when_no_origin_is_configured() {
        assertThat(new HttpPlatformProperties().getCors().enabled()).isFalse();
        assertThat(withOrigins("", "  ").getCors().enabled()).isFalse();
    }

    @Test
    void the_body_limit_is_bounded_and_defaults_to_64_kib() {
        assertThat(new HttpPlatformProperties().getMaxRequestBodyBytes()).isEqualTo(65_536);
        for (long bad : new long[]{0, 1023, 17L * 1024 * 1024, -1}) {
            HttpPlatformProperties p = new HttpPlatformProperties();
            p.setMaxRequestBodyBytes(bad);
            assertThatThrownBy(p::validate).as("" + bad).hasMessageContaining("max-request-body-bytes");
        }
    }

    @Test
    void the_cors_source_never_contains_a_wildcard_or_credentials() {
        var config = HttpPlatformConfig.corsSource(withOrigins("https://cms.tazzzo.com")).getCorsConfigurations().get("/**");
        assertThat(config.getAllowedOrigins()).containsExactly("https://cms.tazzzo.com");
        assertThat(config.getAllowedOriginPatterns()).isNull();
        assertThat(config.getAllowCredentials()).isFalse();
        assertThat(config.getAllowedHeaders()).doesNotContain("*");
        assertThat(config.getAllowedMethods()).doesNotContain("*");
    }
}
