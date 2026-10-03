package com.tazzzo.admin.audit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Raw-query syntax validation (LOW-1): no Spring, no container. */
class RawQuerySyntaxTest {

    /** What a correct container binds for {@code raw}: one value per well-formed component. */
    private static Map<String, String[]> bound(String... namesAndValues) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            m.merge(namesAndValues[i], new String[]{namesAndValues[i + 1]},
                    (a, b) -> new String[]{a[0], b[0]});
        }
        return m;
    }

    /** A bound map with exactly one value per raw component, so ONLY the syntax checks (never the count check) can reject. */
    private static Map<String, String[]> countMatching(String raw) {
        Map<String, String[]> m = new LinkedHashMap<>();
        int n = raw.split("&", -1).length;
        for (int i = 0; i < n; i++) {
            m.put("p" + i, new String[]{"v"});
        }
        return m;
    }

    private static void rejects(String raw) {
        assertThatThrownBy(() -> RawQuerySyntax.requireWellFormed(raw, countMatching(raw)))
                .as(raw).isInstanceOf(AuditQueryRejected.class).hasMessage("query string is malformed");
    }

    private static void accepts(String raw, Map<String, String[]> boundParams) {
        assertThatCode(() -> RawQuerySyntax.requireWellFormed(raw, boundParams)).as(raw).doesNotThrowAnyException();
    }

    @Test
    void invalid_percent_encoding_is_rejected() {
        for (String raw : new String[]{"actorType=%zz", "cursor=%zz", "x=%", "x=%1", "x=%GG", "x=%G1", "x=%1G", "x=a%",
                "x=a%1", "%=1", "%zz=1", "%1=1", "x=%%41", "x=%-1", "x=% 1", "x=%١٢", "x=%１１"}) {
            rejects(raw);
        }
    }

    @Test
    void empty_names_and_empty_components_are_rejected() {
        for (String raw : new String[]{"=foo", "=", "&&", "&", "foo=1&&bar=2", "&foo=1", "foo=1&", "foo=1&=2", "a=1&&", "&&a=1",
                "foo=1&bar=2&"}) {
            rejects(raw);
        }
    }

    @Test
    void malformed_utf8_in_percent_sequences_is_rejected() {
        for (String raw : new String[]{"x=%C3", "x=%ff", "x=%C3%28", "x=%80", "x=%E2%82", "x=%F0%9F%98", "x=%C0%AF", "x=%ED%A0%80"}) {
            rejects(raw);
        }
    }

    @Test
    void raw_non_ascii_is_rejected() {
        rejects("x=é");
    }

    @Test
    void valid_encodings_pass_syntax_validation_and_are_left_to_typed_parsing() {
        accepts("actorId=google%3A110000", bound("actorId", "google:110000"));
        accepts("x=%20", bound("x", " "));
        accepts("x=%2F", bound("x", "/"));
        accepts("x=%2f", bound("x", "/"));
        accepts("x=%C3%A9", bound("x", "é"));
        accepts("x=%E2%82%AC", bound("x", "€"));
        accepts("x=%F0%9F%98%80", bound("x", "😀"));
        accepts("x=%00", bound("x", "\u0000"));
        accepts("a=1&b=%41&c=3", bound("a", "1", "b", "A", "c", "3"));
    }

    @Test
    void a_percent_encoded_percent_is_valid_syntax_and_is_never_decoded_twice() {
        // %2524 is "%24" after the container's ONE decode; syntax validation neither decodes nor returns anything
        accepts("x=%2524", bound("x", "%24"));
        accepts("x=%25zz", bound("x", "%zz"));
    }

    @Test
    void absent_empty_and_name_only_queries_are_valid_syntax() {
        accepts(null, Map.of());
        accepts("", Map.of());
        accepts("cursor", bound("cursor", ""));
        accepts("limit=", bound("limit", ""));
        accepts("a=1&a=2", bound("a", "1", "a", "2"));
    }

    @Test
    void a_parameter_dropped_by_the_container_is_refused_even_when_the_syntax_looks_fine() {
        assertThatThrownBy(() -> RawQuerySyntax.requireWellFormed("a=1&b=2", bound("a", "1")))
                .isInstanceOf(AuditQueryRejected.class).hasMessage("query string is malformed");
        assertThatThrownBy(() -> RawQuerySyntax.requireWellFormed("a=1", bound("a", "1", "b", "2")))
                .isInstanceOf(AuditQueryRejected.class);
        assertThatThrownBy(() -> RawQuerySyntax.requireWellFormed(null, bound("a", "1")))
                .isInstanceOf(AuditQueryRejected.class);
    }

    @Test
    void the_message_is_fixed_and_never_echoes_input() {
        assertThatThrownBy(() -> RawQuerySyntax.requireWellFormed("SECRETNAME=%zz", Map.of()))
                .hasMessage("query string is malformed").satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRETNAME"));
    }
}
