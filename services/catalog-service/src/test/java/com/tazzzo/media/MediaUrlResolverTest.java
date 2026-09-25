package com.tazzzo.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 24 UNIT: URL derivation safety. No CDN is presumed live (STEP 7). */
class MediaUrlResolverTest {

    @Test void resolves_https_url_from_configured_base() {
        MediaUrlResolver r = MediaUrlResolver.of("https://media.test.tazzzo.example");
        assertEquals("https://media.test.tazzzo.example/p/TZP-1/front.webp",
                r.resolve("p/TZP-1/front.webp"));
    }

    @Test void base_change_requires_no_data_rewrite() {
        String key = "p/TZP-1/front.webp"; // the PERSISTED identity never changes
        assertEquals("https://a.example/" + key, MediaUrlResolver.of("https://a.example").resolve(key));
        assertEquals("https://b.example/" + key, MediaUrlResolver.of("https://b.example").resolve(key));
    }

    @Test void trailing_slash_base_produces_no_double_slash() {
        MediaUrlResolver r = MediaUrlResolver.of("https://m.example///");
        assertEquals("https://m.example/k.webp", r.resolve("k.webp"));
    }

    @Test void non_https_and_unsafe_schemes_rejected() {
        for (String bad : List.of("http://m.example", "javascript:alert(1)", "data:text/html;x",
                "file:///etc/passwd", "ftp://m.example", "//m.example", "m.example")) {
            assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of(bad), "should reject: " + bad);
        }
    }

    @Test void base_with_query_or_fragment_rejected() {
        assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of("https://m.example?sig=x"));
        assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of("https://m.example#frag"));
    }

    @Test void unsafe_keys_rejected_at_resolution() {
        MediaUrlResolver r = MediaUrlResolver.of("https://m.example");
        for (String bad : List.of("/abs", "a/../b", "a//b", "", "  ", "a b", "..", "https://x/y",
                "x/./y", "a/", "x/.", "a\\b")) {
            assertThrows(InvalidMediaException.class, () -> r.resolve(bad), "should reject: " + bad);
        }
        // typed domain failure for null too — never a generic NPE (PR-05 review STEP 6)
        assertThrows(InvalidMediaException.class, () -> r.resolve(null));
    }

    @Test void userinfo_in_base_rejected_HIGH() {
        // HIGH (PR-05 review STEP 4): embedded credentials must never reach resolved public URLs.
        assertThrows(InvalidMediaException.class,
                () -> MediaUrlResolver.of("https://user:pass@example.com"));
        assertThrows(InvalidMediaException.class,
                () -> MediaUrlResolver.of("https://user@example.com"));
    }

    @Test void path_prefix_and_port_are_supported_cdn_architecture() {
        assertEquals("https://media.example.com/assets/k.webp",
                MediaUrlResolver.of("https://media.example.com/assets").resolve("k.webp"));
        assertEquals("https://m.example:8443/k.webp",
                MediaUrlResolver.of("https://m.example:8443").resolve("k.webp"));
    }

    @Test void unsafe_base_path_prefix_rejected() {
        assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of("https://m.example/a/../b"));
        assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of("https://m.example/a//b"));
        assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of("https://m.example/%2e%2e"));
    }

    @Test void unconfigured_resolver_fails_safely_only_when_used() {
        MediaUrlResolver r = MediaUrlResolver.unconfigured();
        assertFalse(r.isConfigured());
        InvalidMediaException e = assertThrows(InvalidMediaException.class, () -> r.resolve("k.webp"));
        assertTrue(e.getMessage().contains("no public base configured"));
    }

    @Test void blank_base_rejected() {
        assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of(" "));
        assertThrows(InvalidMediaException.class, () -> MediaUrlResolver.of(null));
    }
}
