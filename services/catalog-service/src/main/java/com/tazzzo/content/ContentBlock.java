package com.tazzzo.content;

import com.tazzzo.media.MediaAsset;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One typed block of customer-facing content: merchandising on HOME (banners, rails, grids) and help on HELP (FAQ entries).
 * A block is authored as DRAFT, goes live only when PUBLISHED and inside its optional time window, and is never deleted
 * (ARCHIVED). The payload is validated per type with closed grammars: links can only point at a product, a category or a
 * search, never at an arbitrary URL; FAQ text is plain text (no markup).
 */
public record ContentBlock(String blockId, Placement placement, Type type, String title, int sort, Status status, Instant startsAt,
                           Instant endsAt, Payload payload, Audience audience, long version, Instant createdAt, Instant updatedAt) {

    public enum Placement { HOME, HELP }

    /**
     * Who may see a block (multichannel D1/D2): the app, the website, or both. A block stored before this field existed has
     * none and reads as {@link #BOTH}, so the public API behaves exactly as before. Only HOME placement is targeted; HELP
     * (FAQ) content is global (D3) and is always {@link #BOTH}.
     */
    public enum Audience {
        APP_ONLY, WEB_ONLY, BOTH;

        public boolean visibleTo(Channel channel) {
            return this == BOTH || (channel == Channel.APP && this == APP_ONLY) || (channel == Channel.WEB && this == WEB_ONLY);
        }
    }

    /** The customer platform asking, from the public {@code channel} query parameter ({@code app} | {@code web}). */
    public enum Channel {
        APP, WEB;

        /** @throws IllegalArgumentException anything but {@code app} or {@code web} (case-sensitive, no whitespace) */
        public static Channel parse(String raw) {
            if ("app".equals(raw)) return APP;
            if ("web".equals(raw)) return WEB;
            throw new IllegalArgumentException("channel must be app or web");
        }
    }

    /** {@code null} (absent on a legacy document or an older client's request) means {@link Audience#BOTH}. */
    public static Audience audience(String raw) {
        if (raw == null) return Audience.BOTH;
        try {
            return Audience.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("audience must be APP_ONLY, WEB_ONLY or BOTH");
        }
    }

    /** @throws IllegalArgumentException a targeted audience on a placement that is global (HELP) */
    public static void requireAudience(Placement placement, Audience audience) {
        if (audience == null) throw new IllegalArgumentException("audience is required");
        if (placement == Placement.HELP && audience != Audience.BOTH) {
            throw new IllegalArgumentException("HELP content is global: audience must be BOTH");
        }
    }

    public enum Type {
        BANNER(Placement.HOME), PRODUCT_RAIL(Placement.HOME), CATEGORY_GRID(Placement.HOME), FAQ(Placement.HELP);

        private final Placement placement;

        Type(Placement placement) {
            this.placement = placement;
        }

        /** The only placement this type may be authored on. */
        public Placement placement() {
            return placement;
        }
    }

    /** The help-centre sections (blueprint T49); closed so the app can map each to a fixed heading. */
    public enum FaqCategory { DELIVERY, PRODUCT, CLUB, PAYMENT, REFUND, ACCOUNT }

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    /**
     * {@code imageAssetKey}/{@code link} for BANNER; {@code ids} (products or category nodes) for the rails/grids;
     * {@code faqCategory}/{@code question}/{@code answer} for FAQ. Fields that do not belong to the type must be absent.
     */
    public record Payload(String imageAssetKey, String link, List<String> ids, String faqCategory, String question, String answer) {
        public Payload {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }

        /** A merchandising payload (no FAQ fields). */
        public Payload(String imageAssetKey, String link, List<String> ids) {
            this(imageAssetKey, link, ids, null, null, null);
        }

        public static Payload faq(String category, String question, String answer) {
            return new Payload(null, null, null, category, question, answer);
        }

        boolean hasFaqFields() {
            return faqCategory != null || question != null || answer != null;
        }
    }

    public static final int MAX_TITLE = 80;
    public static final int MAX_RAIL = 20;
    public static final int MAX_GRID = 12;
    public static final int MAX_QUESTION = 200;
    public static final int MAX_ANSWER = 2000;
    static final Pattern PRODUCT_ID = Pattern.compile("TZP-[A-Za-z0-9-]{1,40}");
    static final Pattern NODE_ID = Pattern.compile("TZ[SCGV]-[0-9]{6}");
    static final Pattern LINK = Pattern.compile("(product:TZP-[A-Za-z0-9-]{1,40})|(category:TZ[SCGV]-[0-9]{6})|(search:[\\p{L}\\p{N} ]{2,64})");

    /** @throws IllegalArgumentException the first violated rule */
    public static void validate(Type type, String title, int sort, Instant startsAt, Instant endsAt, Payload p) {
        if (type == null || p == null) throw new IllegalArgumentException("type and payload are required");
        if (title == null || title.isBlank() || title.length() > MAX_TITLE || !title.equals(title.strip())
                || title.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            throw new IllegalArgumentException("title must be 1.." + MAX_TITLE + " chars of plain text");
        }
        if (sort < 0 || sort > 10_000) throw new IllegalArgumentException("sort must be within 0..10000");
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) throw new IllegalArgumentException("startsAt must be before endsAt");
        if (type != Type.FAQ && p.hasFaqFields()) throw new IllegalArgumentException("FAQ fields belong to FAQ blocks only");
        switch (type) {
            case BANNER -> {
                if (!MediaAsset.isSafeKey(p.imageAssetKey())) throw new IllegalArgumentException("banner needs a safe imageAssetKey");
                if (p.link() == null || !LINK.matcher(p.link()).matches()) {
                    throw new IllegalArgumentException("banner link must be product:<id>, category:<node id> or search:<text>");
                }
                if (!p.ids().isEmpty()) throw new IllegalArgumentException("a banner carries no ids");
            }
            case PRODUCT_RAIL -> requireIds(p, PRODUCT_ID, MAX_RAIL, "product");
            case CATEGORY_GRID -> requireIds(p, NODE_ID, MAX_GRID, "category node");
            case FAQ -> {
                if (p.imageAssetKey() != null || p.link() != null || !p.ids().isEmpty()) {
                    throw new IllegalArgumentException("an FAQ carries only faqCategory, question and answer");
                }
                faqCategory(p.faqCategory());
                plainText(p.question(), MAX_QUESTION, false, "question");
                plainText(p.answer(), MAX_ANSWER, true, "answer");
            }
        }
    }

    /** @throws IllegalArgumentException a type authored on another placement (an FAQ on HOME, a banner on HELP) */
    public static void requirePlacement(Placement placement, Type type) {
        if (placement == null || type == null || type.placement() != placement) {
            throw new IllegalArgumentException("type " + type + " belongs on placement " + (type == null ? "?" : type.placement()));
        }
    }

    public static FaqCategory faqCategory(String raw) {
        try {
            return FaqCategory.valueOf(raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("faqCategory must be one of DELIVERY, PRODUCT, CLUB, PAYMENT, REFUND, ACCOUNT");
        }
    }

    /** Plain text: trimmed, bounded, no control characters (a newline only where allowed), no markup brackets. */
    private static void plainText(String s, int max, boolean newlines, String what) {
        if (s == null || s.isBlank() || s.length() > max || !s.equals(s.strip())
                || s.chars().anyMatch(c -> (c < 0x20 && !(newlines && c == '\n')) || c == 0x7F || c == '<' || c == '>')) {
            throw new IllegalArgumentException(what + " must be 1.." + max + " chars of plain text (no markup)");
        }
    }

    private static void requireIds(Payload p, Pattern shape, int max, String what) {
        if (p.imageAssetKey() != null || p.link() != null) throw new IllegalArgumentException("only ids are allowed for this type");
        if (p.ids().isEmpty() || p.ids().size() > max) throw new IllegalArgumentException("1.." + max + " " + what + " ids required");
        if (new HashSet<>(p.ids()).size() != p.ids().size()) throw new IllegalArgumentException("duplicate ids");
        for (String id : p.ids()) {
            if (id == null || !shape.matcher(id).matches()) throw new IllegalArgumentException("invalid " + what + " id");
        }
    }

    public boolean isLiveAt(Instant now) {
        return status == Status.PUBLISHED && (startsAt == null || !now.isBefore(startsAt)) && (endsAt == null || now.isBefore(endsAt));
    }
}
