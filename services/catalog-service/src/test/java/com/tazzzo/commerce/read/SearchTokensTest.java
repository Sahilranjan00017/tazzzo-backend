package com.tazzzo.commerce.read;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchTokensTest {

    @Test
    void stored_tokens_are_lowercased_distinct_letter_digit_runs_of_title_and_brand() {
        assertThat(SearchTokens.of("India Gate Basmati Rice 5kg (Premium)", "IND-GATE"))
                .containsExactly("india", "gate", "basmati", "rice", "5kg", "premium", "ind");
        assertThat(SearchTokens.of("बासमती चावल", null)).containsExactly("बासमती", "चावल");
        assertThat(SearchTokens.of(null, null)).isEmpty();
        assertThat(SearchTokens.of("a".repeat(10) + " " + String.join(" ", java.util.Collections.nCopies(60, "x")).replace("x", "y1"), "b"))
                .hasSizeLessThanOrEqualTo(SearchTokens.MAX_STORED_TOKENS);
    }

    @Test
    void query_tokens_use_the_same_normalisation_drop_single_characters_and_are_bounded() {
        assertThat(SearchTokens.query("  Basmati, RICE 5kg a ")).containsExactly("basmati", "rice", "5kg");
        assertThat(SearchTokens.query("")).isEmpty();
        assertThat(SearchTokens.query(null)).isEmpty();
        assertThat(SearchTokens.query("a b c")).as("only single characters: nothing searchable").isEmpty();
        assertThat(SearchTokens.query("{\"$ne\":null}")).containsExactly("ne", "null");
        assertThatThrownBy(() -> SearchTokens.query("x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchTokens.query("y".repeat(33))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchTokens.query("aa bb cc dd ee ff")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SearchTokens.query("aa bb cc dd ee")).hasSize(5);
    }

    @Test
    void a_query_token_is_always_a_prefix_of_what_the_same_text_stores() {
        List<String> stored = SearchTokens.of("Daawat Rozana Gold Basmati", "DAAWAT");
        for (String q : SearchTokens.query("daaw ROZ basm")) {
            assertThat(stored).anyMatch(t -> t.startsWith(q));
        }
    }
}
