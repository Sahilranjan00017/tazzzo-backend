package com.tazzzo.content;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentModelTest {

    static ContentBlock.Payload banner(String key, String link) {
        return new ContentBlock.Payload(key, link, null);
    }

    static ContentBlock.Payload ids(String... ids) {
        return new ContentBlock.Payload(null, null, List.of(ids));
    }

    static void ok(ContentBlock.Type t, ContentBlock.Payload p) {
        ContentBlock.validate(t, "Title", 1, null, null, p);
    }

    static void bad(String why, ContentBlock.Type t, String title, int sort, Instant s, Instant e, ContentBlock.Payload p) {
        assertThatThrownBy(() -> ContentBlock.validate(t, title, sort, s, e, p)).as(why).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valid_blocks_of_each_type() {
        ok(ContentBlock.Type.BANNER, banner("cms/home/diwali.webp", "product:TZP-100001"));
        ok(ContentBlock.Type.BANNER, banner("cms/home/rice.webp", "category:TZC-000001"));
        ok(ContentBlock.Type.BANNER, banner("cms/home/s.webp", "search:basmati rice"));
        ok(ContentBlock.Type.PRODUCT_RAIL, ids("TZP-1", "TZP-2"));
        ok(ContentBlock.Type.CATEGORY_GRID, ids("TZS-000001", "TZC-000002", "TZV-000001"));
    }

    @Test
    void links_are_a_closed_grammar_never_an_arbitrary_url() {
        for (String link : new String[]{"https://evil.example", "javascript:alert(1)", "product:", "product:../x", "category:TZX-000001",
                "search:a", "search:<script>", "search:" + "x".repeat(65), "PRODUCT:TZP-1", "product:TZP-1 ", null}) {
            bad(String.valueOf(link), ContentBlock.Type.BANNER, "T", 1, null, null, banner("cms/x.webp", link));
        }
        bad("unsafe key", ContentBlock.Type.BANNER, "T", 1, null, null, banner("../etc/passwd", "search:rice"));
        bad("banner with ids", ContentBlock.Type.BANNER, "T", 1, null, null, new ContentBlock.Payload("cms/x.webp", "search:rice", List.of("TZP-1")));
    }

    @Test
    void rails_and_grids_are_bounded_distinct_and_well_formed() {
        bad("empty rail", ContentBlock.Type.PRODUCT_RAIL, "T", 1, null, null, ids());
        bad("duplicate", ContentBlock.Type.PRODUCT_RAIL, "T", 1, null, null, ids("TZP-1", "TZP-1"));
        bad("too many", ContentBlock.Type.PRODUCT_RAIL, "T", 1, null, null,
                new ContentBlock.Payload(null, null, java.util.stream.IntStream.range(0, 21).mapToObj(i -> "TZP-" + i).toList()));
        bad("not a product", ContentBlock.Type.PRODUCT_RAIL, "T", 1, null, null, ids("TZC-000001"));
        bad("not a node", ContentBlock.Type.CATEGORY_GRID, "T", 1, null, null, ids("TZP-1"));
        bad("too many nodes", ContentBlock.Type.CATEGORY_GRID, "T", 1, null, null,
                new ContentBlock.Payload(null, null, java.util.stream.IntStream.range(0, 13).mapToObj(i -> String.format("TZC-%06d", i)).toList()));
        bad("a rail with a link", ContentBlock.Type.PRODUCT_RAIL, "T", 1, null, null, new ContentBlock.Payload(null, "search:rice", List.of("TZP-1")));
    }

    @Test
    void title_sort_and_window_are_validated_and_the_window_decides_liveness() {
        bad("blank title", ContentBlock.Type.PRODUCT_RAIL, " ", 1, null, null, ids("TZP-1"));
        bad("long title", ContentBlock.Type.PRODUCT_RAIL, "x".repeat(81), 1, null, null, ids("TZP-1"));
        bad("control char", ContentBlock.Type.PRODUCT_RAIL, "a\nb", 1, null, null, ids("TZP-1"));
        bad("negative sort", ContentBlock.Type.PRODUCT_RAIL, "T", -1, null, null, ids("TZP-1"));
        Instant t = Instant.parse("2026-10-05T00:00:00Z");
        bad("empty window", ContentBlock.Type.PRODUCT_RAIL, "T", 1, t, t, ids("TZP-1"));
        ContentBlock b = new ContentBlock("CB_x", ContentBlock.Placement.HOME, ContentBlock.Type.PRODUCT_RAIL, "T", 1,
                ContentBlock.Status.PUBLISHED, t, t.plusSeconds(60), ids("TZP-1"), 1, t, t);
        assertThat(b.isLiveAt(t.minusMillis(1))).isFalse();
        assertThat(b.isLiveAt(t)).isTrue();
        assertThat(b.isLiveAt(t.plusSeconds(60))).as("end is exclusive").isFalse();
    }

    @Test
    void app_config_rules() {
        AppConfig.DEFAULT.validate();
        new AppConfig(false, true, "Back at 6 pm", "1.2", "1.10", "2.0.1", "2.0.1", "+919876543210", "help@tazzzo.com", 3).validate();
        assertThat(AppConfig.compare("1.10", "1.9")).isPositive();
        assertThat(AppConfig.compare("1.2", "1.2.0")).isZero();
        for (AppConfig bad : List.of(
                new AppConfig(true, true, null, null, null, null, null, null, null, 0),
                new AppConfig(true, false, "bad\u0000", null, null, null, null, null, null, 0),
                new AppConfig(true, false, null, "1.x", null, null, null, null, null, 0),
                new AppConfig(true, false, null, "2.0", "1.9", null, null, null, null, 0),
                new AppConfig(true, false, null, null, null, "3", "2", null, null, 0),
                new AppConfig(true, false, null, null, null, null, null, "9876543210", null, 0),
                new AppConfig(true, false, null, null, null, null, null, null, "not-an-email", 0))) {
            assertThatThrownBy(bad::validate).as(bad.toString()).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
