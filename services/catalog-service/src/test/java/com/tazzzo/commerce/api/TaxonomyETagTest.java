package com.tazzzo.commerce.api;

import com.tazzzo.commerce.api.dto.NodeDto;
import com.tazzzo.commerce.api.dto.NodeListResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-10C final review #1/#2 — the taxonomy ETag's canonicalization must be structurally
 * unambiguous (length-prefixed, never delimiter-joined text) and its {@code If-None-Match}
 * comparison must be WEAK per RFC 7232, while the response always emits a strong tag.
 */
class TaxonomyETagTest {

    private static NodeListResponse body(String release, NodeDto... items) {
        return new NodeListResponse(release, List.of(items), "req_ignored");
    }

    @Test void deterministic_for_identical_input() {
        NodeListResponse b = body("R1", new NodeDto("TZC-1", "Rice"));
        assertThat(TaxonomyETag.compute("categories", null, b))
                .isEqualTo(TaxonomyETag.compute("categories", null, b));
    }

    @Test void the_response_etag_is_always_a_strong_quoted_tag() {
        String etag = TaxonomyETag.compute("categories", null, body("R1", new NodeDto("A", "B")));
        assertThat(etag).startsWith("\"").endsWith("\"").doesNotStartWith("W/");
    }

    @Test void never_includes_the_request_id() {
        NodeListResponse b = new NodeListResponse("R1", List.of(new NodeDto("A", "B")), "req_aaaaaaaaaaaaaaaa");
        NodeListResponse sameButDifferentRequestId =
                new NodeListResponse("R1", List.of(new NodeDto("A", "B")), "req_bbbbbbbbbbbbbbbb");
        assertThat(TaxonomyETag.compute("categories", null, b))
                .isEqualTo(TaxonomyETag.compute("categories", null, sameButDifferentRequestId));
    }

    // ---------- canonicalization ambiguity (final review #1) ----------

    @Test void a_name_containing_a_space_does_not_collide_with_split_items() {
        NodeListResponse oneItemWithSpace = body("R1", new NodeDto("A", "Rice Basmati"));
        NodeListResponse twoItemsSplitOnSpace = body("R1", new NodeDto("A", "Rice"), new NodeDto("Basmati", ""));
        assertThat(TaxonomyETag.compute("categories", null, oneItemWithSpace))
                .as("a delimiter-joined encoding could conflate these; length-prefixing must not")
                .isNotEqualTo(TaxonomyETag.compute("categories", null, twoItemsSplitOnSpace));
    }

    @Test void a_name_containing_a_colon_does_not_collide_with_an_id_name_boundary_shift() {
        NodeListResponse itemWithColonInName = body("R1", new NodeDto("A", "B:C"));
        NodeListResponse differentIdNameSplit = body("R1", new NodeDto("A:B", "C"));
        assertThat(TaxonomyETag.compute("categories", null, itemWithColonInName))
                .as("the id/name boundary must never be reconstructable from content")
                .isNotEqualTo(TaxonomyETag.compute("categories", null, differentIdNameSplit));
    }

    @Test void values_containing_former_separator_characters_survive_without_collision() {
        // exercises literal ' ' and ':' -- the OLD delimiter-joined encoding's separators --
        // appearing inside id/name/route/nodeId/release simultaneously.
        NodeListResponse a = body("R1", new NodeDto("id a", "name:b"));
        NodeListResponse b = body("R1", new NodeDto("id", "a name:b")); // shifted split, same raw chars
        assertThat(TaxonomyETag.compute("categories", "node x", a))
                .isNotEqualTo(TaxonomyETag.compute("categories", "node x", b));
    }

    @Test void one_item_vs_two_item_ambiguity_attempt_never_collides() {
        // "id1" + "name1" + "id2" + "name2" vs a single item whose id/name happen to
        // concatenate to the same raw character sequence, if it were delimiter-joined.
        NodeListResponse twoItems = body("R1", new NodeDto("id1", "name1"), new NodeDto("id2", "name2"));
        NodeListResponse oneItem = body("R1", new NodeDto("id1", "name1id2name2"));
        assertThat(TaxonomyETag.compute("categories", null, twoItems))
                .isNotEqualTo(TaxonomyETag.compute("categories", null, oneItem));
    }

    @Test void a_null_node_id_never_collides_with_an_empty_string_node_id() {
        NodeListResponse b = body("R1", new NodeDto("A", "B"));
        assertThat(TaxonomyETag.compute("children", null, b))
                .as("explicit null sentinel, never collapsed to an empty string")
                .isNotEqualTo(TaxonomyETag.compute("children", "", b));
    }

    @Test void item_order_is_bound_into_the_hash() {
        NodeListResponse ascending = body("R1", new NodeDto("A", "Alpha"), new NodeDto("B", "Beta"));
        NodeListResponse descending = body("R1", new NodeDto("B", "Beta"), new NodeDto("A", "Alpha"));
        assertThat(TaxonomyETag.compute("categories", null, ascending))
                .isNotEqualTo(TaxonomyETag.compute("categories", null, descending));
    }

    @Test void route_and_node_id_are_both_bound_into_the_hash() {
        NodeListResponse b = body("R1", new NodeDto("A", "B"));
        assertThat(TaxonomyETag.compute("categories", null, b))
                .isNotEqualTo(TaxonomyETag.compute("children", "X", b));
    }

    // ---------- weak If-None-Match comparison (final review #2) ----------

    @Test void an_exact_strong_match_is_a_hit() {
        assertThat(TaxonomyETag.matches("\"abc\"", "\"abc\"")).isTrue();
    }

    @Test void a_weak_prefixed_request_value_still_matches_our_strong_tag() {
        assertThat(TaxonomyETag.matches("W/\"abc\"", "\"abc\"")).isTrue();
        assertThat(TaxonomyETag.matches("w/\"abc\"", "\"abc\"")).isTrue();
    }

    @Test void a_multi_value_list_matches_if_any_entry_matches() {
        assertThat(TaxonomyETag.matches("\"other\", W/\"abc\"", "\"abc\"")).isTrue();
        assertThat(TaxonomyETag.matches("W/\"abc\", \"other\"", "\"abc\"")).isTrue();
    }

    @Test void the_wildcard_always_matches() {
        assertThat(TaxonomyETag.matches("*", "\"anything\"")).isTrue();
    }

    @Test void a_non_matching_or_malformed_value_never_matches() {
        assertThat(TaxonomyETag.matches("\"other\"", "\"abc\"")).isFalse();
        assertThat(TaxonomyETag.matches("", "\"abc\"")).isFalse();
        assertThat(TaxonomyETag.matches(null, "\"abc\"")).isFalse();
        assertThat(TaxonomyETag.matches("garbage, W/\"nope\"", "\"abc\"")).isFalse();
    }

    @Test void generation_never_weakens_the_response_tag_itself() {
        // the response ALWAYS emits a strong tag, regardless of what the client sent
        String etag = TaxonomyETag.compute("categories", null, body("R1", new NodeDto("A", "B")));
        assertThat(etag).doesNotStartWith("W/");
    }
}
