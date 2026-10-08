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
                ContentBlock.Status.PUBLISHED, t, t.plusSeconds(60), ids("TZP-1"), ContentBlock.Audience.BOTH, 1, t, t);
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

    static ContentBlock.Payload faq(String category, String q, String a) {
        return ContentBlock.Payload.faq(category, q, a);
    }

    @Test
    void faq_payloads_are_plain_text_in_a_closed_category() {
        ok(ContentBlock.Type.FAQ, faq("DELIVERY", "When will my order arrive?", "Within the slot you chose.\nWe call before arriving."));
        for (String c : new String[]{"DELIVERY", "PRODUCT", "CLUB", "PAYMENT", "REFUND", "ACCOUNT"}) ok(ContentBlock.Type.FAQ, faq(c, "Q?", "A."));
        bad("unknown category", ContentBlock.Type.FAQ, "T", 1, null, null, faq("SHIPPING", "Q?", "A."));
        bad("missing category", ContentBlock.Type.FAQ, "T", 1, null, null, faq(null, "Q?", "A."));
        bad("blank question", ContentBlock.Type.FAQ, "T", 1, null, null, faq("CLUB", " ", "A."));
        bad("long question", ContentBlock.Type.FAQ, "T", 1, null, null, faq("CLUB", "q".repeat(201), "A."));
        bad("newline in question", ContentBlock.Type.FAQ, "T", 1, null, null, faq("CLUB", "a\nb", "A."));
        bad("markup in answer", ContentBlock.Type.FAQ, "T", 1, null, null, faq("CLUB", "Q?", "<script>x</script>"));
        bad("control char in answer", ContentBlock.Type.FAQ, "T", 1, null, null, faq("CLUB", "Q?", "a\u0007b"));
        bad("long answer", ContentBlock.Type.FAQ, "T", 1, null, null, faq("CLUB", "Q?", "a".repeat(2001)));
        ok(ContentBlock.Type.FAQ, faq("CLUB", "q".repeat(200), "a".repeat(2000)));
        bad("faq with a link", ContentBlock.Type.FAQ, "T", 1, null, null,
                new ContentBlock.Payload(null, "product:TZP-1", null, "CLUB", "Q?", "A."));
        bad("a banner with faq text", ContentBlock.Type.BANNER, "T", 1, null, null,
                new ContentBlock.Payload("cms/home/a.webp", "product:TZP-1", null, null, "Q?", null));
    }

    @Test
    void each_type_belongs_to_exactly_one_placement() {
        ContentBlock.requirePlacement(ContentBlock.Placement.HELP, ContentBlock.Type.FAQ);
        ContentBlock.requirePlacement(ContentBlock.Placement.HOME, ContentBlock.Type.BANNER);
        assertThatThrownBy(() -> ContentBlock.requirePlacement(ContentBlock.Placement.HOME, ContentBlock.Type.FAQ))
                .isInstanceOf(IllegalArgumentException.class);
        for (ContentBlock.Type t : new ContentBlock.Type[]{ContentBlock.Type.BANNER, ContentBlock.Type.PRODUCT_RAIL, ContentBlock.Type.CATEGORY_GRID}) {
            assertThatThrownBy(() -> ContentBlock.requirePlacement(ContentBlock.Placement.HELP, t)).as(t.name())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    static AppConfig legal(String terms, String privacy, String refund) {
        return new AppConfig(true, false, null, null, null, null, null, null, null, terms, privacy, refund, 0);
    }

    @Test
    void legal_links_are_absolute_https_urls_or_absent() {
        legal(null, null, null).validate();
        legal("https://tazzzo.com/terms", "https://tazzzo.com/privacy?v=2", "https://help.tazzzo.com/refunds#policy").validate();
        for (String bad : new String[]{"http://tazzzo.com/terms", "tazzzo.com/terms", "//tazzzo.com/terms", "https://", "https:///x",
                "javascript:alert(1)", "https://user:pw@tazzzo.com/terms", "https://tazzzo.com/te rms", "https://tazzzo.com/\nx",
                "ftp://tazzzo.com/terms", "https://tazzzo.com/" + "x".repeat(500), ""}) {
            assertThatThrownBy(() -> legal(bad, null, null).validate()).as("terms " + bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> legal(null, bad, null).validate()).as("privacy " + bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> legal(null, null, bad).validate()).as("refund " + bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void audience_visibility_follows_the_channel_and_legacy_means_both() {
        assertThat(ContentBlock.audience(null)).isEqualTo(ContentBlock.Audience.BOTH);
        assertThat(ContentBlock.audience("APP_ONLY")).isEqualTo(ContentBlock.Audience.APP_ONLY);
        assertThatThrownBy(() -> ContentBlock.audience("app")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audience");
        assertThatThrownBy(() -> ContentBlock.audience("ALL")).isInstanceOf(IllegalArgumentException.class);
        for (ContentBlock.Channel c : ContentBlock.Channel.values()) assertThat(ContentBlock.Audience.BOTH.visibleTo(c)).isTrue();
        assertThat(ContentBlock.Audience.BOTH.visibleTo(null)).as("an unidentified platform sees BOTH").isTrue();
        assertThat(ContentBlock.Audience.APP_ONLY.visibleTo(ContentBlock.Channel.APP)).isTrue();
        assertThat(ContentBlock.Audience.APP_ONLY.visibleTo(ContentBlock.Channel.WEB)).isFalse();
        assertThat(ContentBlock.Audience.APP_ONLY.visibleTo(null)).as("never leaks to an unidentified platform").isFalse();
        assertThat(ContentBlock.Audience.WEB_ONLY.visibleTo(ContentBlock.Channel.WEB)).isTrue();
        assertThat(ContentBlock.Audience.WEB_ONLY.visibleTo(ContentBlock.Channel.APP)).isFalse();
        assertThat(ContentBlock.Audience.WEB_ONLY.visibleTo(null)).isFalse();
    }

    @Test
    void channel_is_a_closed_lowercase_choice_and_help_content_is_global() {
        assertThat(ContentBlock.Channel.parse("app")).isEqualTo(ContentBlock.Channel.APP);
        assertThat(ContentBlock.Channel.parse("web")).isEqualTo(ContentBlock.Channel.WEB);
        for (String bad : new String[]{"APP", "ios", "android", " app", "", null}) {
            assertThatThrownBy(() -> ContentBlock.Channel.parse(bad)).as(String.valueOf(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        ContentBlock.requireAudience(ContentBlock.Placement.HOME, ContentBlock.Audience.APP_ONLY);
        ContentBlock.requireAudience(ContentBlock.Placement.HELP, ContentBlock.Audience.BOTH);
        assertThatThrownBy(() -> ContentBlock.requireAudience(ContentBlock.Placement.HELP, ContentBlock.Audience.WEB_ONLY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("global");
        assertThatThrownBy(() -> ContentBlock.requireAudience(ContentBlock.Placement.HOME, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_effective_status_is_derived_from_status_and_window() {
        Instant now = Instant.parse("2026-10-08T10:00:00Z");
        java.util.function.BiFunction<Instant, Instant, ContentBlock> published = (from, to) -> new ContentBlock("CB_x", ContentBlock.Placement.HOME,
                ContentBlock.Type.PRODUCT_RAIL, "T", 1, ContentBlock.Status.PUBLISHED, from, to, ids("TZP-1"), ContentBlock.Audience.BOTH, 1, now, now);
        assertThat(published.apply(null, null).effectiveAt(now)).isEqualTo(ContentBlock.Effective.LIVE);
        assertThat(published.apply(now.plusSeconds(1), null).effectiveAt(now)).isEqualTo(ContentBlock.Effective.SCHEDULED);
        assertThat(published.apply(now, null).effectiveAt(now)).as("start is inclusive").isEqualTo(ContentBlock.Effective.LIVE);
        assertThat(published.apply(null, now).effectiveAt(now)).as("end is exclusive").isEqualTo(ContentBlock.Effective.EXPIRED);
        ContentBlock draft = new ContentBlock("CB_x", ContentBlock.Placement.HOME, ContentBlock.Type.PRODUCT_RAIL, "T", 1,
                ContentBlock.Status.DRAFT, null, null, ids("TZP-1"), ContentBlock.Audience.BOTH, 1, now, now);
        assertThat(draft.effectiveAt(now)).isEqualTo(ContentBlock.Effective.DRAFT);
    }

    @Test
    void banner_presentation_fields_are_plain_text_and_banner_only() {
        ContentBlock.validate(ContentBlock.Type.BANNER, "T", 1, null, null,
                ContentBlock.Payload.banner("c/home/a.webp", "search:rice", "Sub", "Alt text", "c/home/wide.webp"));
        bad("subtitle with a newline", ContentBlock.Type.BANNER, "T", 1, null, null,
                ContentBlock.Payload.banner("c/home/a.webp", "search:rice", "a\nb", null, null));
        bad("alt with markup", ContentBlock.Type.BANNER, "T", 1, null, null,
                ContentBlock.Payload.banner("c/home/a.webp", "search:rice", null, "<b>", null));
        bad("bidi override in alt", ContentBlock.Type.BANNER, "T", 1, null, null,
                ContentBlock.Payload.banner("c/home/a.webp", "search:rice", null, "abc\u202Edef", null));
        bad("zero-width space in subtitle", ContentBlock.Type.BANNER, "T", 1, null, null,
                ContentBlock.Payload.banner("c/home/a.webp", "search:rice", "a\u200Bb", null, null));
        ContentBlock.validate(ContentBlock.Type.BANNER, "T", 1, null, null,
                ContentBlock.Payload.banner("c/home/a.webp", "search:rice", "ताज़ा आम", "नारंगी आम की टोकरी", null));
        bad("unsafe desktop key", ContentBlock.Type.BANNER, "T", 1, null, null,
                ContentBlock.Payload.banner("c/home/a.webp", "search:rice", null, null, "/etc/passwd"));
        bad("alt on a grid", ContentBlock.Type.CATEGORY_GRID, "T", 1, null, null,
                new ContentBlock.Payload(null, null, List.of("TZC-000001"), null, null, null, null, "Alt", null));
    }
}
