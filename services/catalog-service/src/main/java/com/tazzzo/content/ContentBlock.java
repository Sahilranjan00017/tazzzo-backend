package com.tazzzo.content;

import com.tazzzo.media.MediaAsset;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One typed block of customer-facing content: merchandising on HOME (banners, rails, grids) and help on HELP (FAQ entries, legal documents).
 * A block is authored as DRAFT, goes live only when PUBLISHED and inside its optional time window, and is never deleted
 * (ARCHIVED). The payload is validated per type with closed grammars: links can only point at a product, a category or a
 * search, never at an arbitrary URL; FAQ text is plain text (no markup).
 */
public record ContentBlock(String blockId, Placement placement, Type type, String title, int sort, Status status, Instant startsAt,
                           Instant endsAt, Payload payload, Audience audience, long version, Instant createdAt, Instant updatedAt,
                           String createdBy, String updatedBy) {

    /** Without authorship (a block stored before {@code createdBy}/{@code updatedBy} were recorded reads them as null). */
    public ContentBlock(String blockId, Placement placement, Type type, String title, int sort, Status status, Instant startsAt,
                        Instant endsAt, Payload payload, Audience audience, long version, Instant createdAt, Instant updatedAt) {
        this(blockId, placement, type, title, sort, status, startsAt, endsAt, payload, audience, version, createdAt, updatedAt, null, null);
    }

    /**
     * What an editor needs to know about a block at {@code now}, derived (never stored) from status and window: a
     * PUBLISHED block is SCHEDULED before {@code startsAt}, LIVE inside its window and EXPIRED after {@code endsAt}.
     */
    public enum Effective { DRAFT, SCHEDULED, LIVE, EXPIRED, ARCHIVED }

    public Effective effectiveAt(Instant now) {
        return switch (status) {
            case DRAFT -> Effective.DRAFT;
            case ARCHIVED -> Effective.ARCHIVED;
            case PUBLISHED -> startsAt != null && now.isBefore(startsAt) ? Effective.SCHEDULED
                    : endsAt != null && !now.isBefore(endsAt) ? Effective.EXPIRED : Effective.LIVE;
        };
    }

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
        BANNER(Placement.HOME), PRODUCT_RAIL(Placement.HOME), CATEGORY_GRID(Placement.HOME), FAQ(Placement.HELP),
        /** A legal document (terms, privacy) shown as plain paragraphs; at most one may be live per {@link LegalSlug}. */
        LEGAL(Placement.HELP);

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

    /** The legal documents the platform publishes; closed so the public path segment can only be one of these. */
    public enum LegalSlug {
        TERMS, PRIVACY;

        /** The public path segment: exactly {@code terms} or {@code privacy} (lowercase). */
        public String path() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** @return the slug for a public path segment, or null for anything but exactly {@code terms} / {@code privacy} */
        public static LegalSlug fromPath(String segment) {
            if ("terms".equals(segment)) return TERMS;
            if ("privacy".equals(segment)) return PRIVACY;
            return null;
        }
    }

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    /**
     * {@code imageAssetKey}/{@code link} for BANNER, optionally {@code subtitle}, {@code altText} and a wide
     * {@code desktopImageAssetKey} (absent = the website uses {@code imageAssetKey} everywhere); {@code ids} (products or
     * category nodes) for the rails/grids; {@code faqCategory}/{@code question}/{@code answer} for FAQ. Fields that do not
     * belong to the type must be absent. {@code legalSlug}/{@code body}/{@code effectiveDate} (yyyy-MM-dd, optional) are
     * for LEGAL: the body is plain text, paragraphs separated by blank lines.
     */
    public record Payload(String imageAssetKey, String link, List<String> ids, String faqCategory, String question, String answer,
                          String subtitle, String altText, String desktopImageAssetKey, String legalSlug, String body,
                          String effectiveDate) {
        public Payload {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }

        /** Without the LEGAL fields. */
        public Payload(String imageAssetKey, String link, List<String> ids, String faqCategory, String question, String answer,
                       String subtitle, String altText, String desktopImageAssetKey) {
            this(imageAssetKey, link, ids, faqCategory, question, answer, subtitle, altText, desktopImageAssetKey, null, null, null);
        }

        public static Payload legal(String legalSlug, String body, String effectiveDate) {
            return new Payload(null, null, null, null, null, null, null, null, null, legalSlug, body, effectiveDate);
        }

        boolean hasLegalFields() {
            return legalSlug != null || body != null || effectiveDate != null;
        }

        public Payload(String imageAssetKey, String link, List<String> ids, String faqCategory, String question, String answer) {
            this(imageAssetKey, link, ids, faqCategory, question, answer, null, null, null);
        }

        /** A banner payload with its optional presentation fields. */
        public static Payload banner(String imageAssetKey, String link, String subtitle, String altText, String desktopImageAssetKey) {
            return new Payload(imageAssetKey, link, null, null, null, null, subtitle, altText, desktopImageAssetKey);
        }

        boolean hasBannerOnlyFields() {
            return subtitle != null || altText != null || desktopImageAssetKey != null;
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
    public static final int MAX_SUBTITLE = 120;
    public static final int MAX_ALT = 300;
    public static final int MAX_LEGAL_BODY = 60_000;
    private static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    static final Pattern PRODUCT_ID = com.tazzzo.catalog.domain.ProductIds.PATTERN;
    static final Pattern NODE_ID = Pattern.compile("TZ[SCGV]-[0-9]{6}");
    static final Pattern LINK = Pattern.compile("(product:" + com.tazzzo.catalog.domain.ProductIds.BODY + ")|(category:TZ[SCGV]-[0-9]{6})|(search:[\\p{L}\\p{M}\\p{N} ]{2,64})");

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
        if (type != Type.LEGAL && p.hasLegalFields()) throw new IllegalArgumentException("legalSlug, body and effectiveDate belong to LEGAL blocks only");
        if (type != Type.BANNER && p.hasBannerOnlyFields()) {
            throw new IllegalArgumentException("subtitle, altText and desktopImageAssetKey belong to BANNER blocks only");
        }
        switch (type) {
            case BANNER -> {
                if (!MediaAsset.isSafeKey(p.imageAssetKey())) throw new IllegalArgumentException("banner needs a safe imageAssetKey");
                if (p.link() == null || !LINK.matcher(p.link()).matches()) {
                    throw new IllegalArgumentException("banner link must be product:<id>, category:<node id> or search:<text>");
                }
                if (!p.ids().isEmpty()) throw new IllegalArgumentException("a banner carries no ids");
                if (p.desktopImageAssetKey() != null && !MediaAsset.isSafeKey(p.desktopImageAssetKey())) {
                    throw new IllegalArgumentException("desktopImageAssetKey must be a safe key");
                }
                if (p.subtitle() != null) displayText(p.subtitle(), MAX_SUBTITLE, "subtitle");
                if (p.altText() != null) displayText(p.altText(), MAX_ALT, "altText");
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
            case LEGAL -> {
                if (p.imageAssetKey() != null || p.link() != null || !p.ids().isEmpty() || p.hasFaqFields()) {
                    throw new IllegalArgumentException("a LEGAL block carries only legalSlug, body and effectiveDate");
                }
                legalSlug(p.legalSlug());
                plainText(p.body(), MAX_LEGAL_BODY, true, "body");
                if (p.body().codePoints().anyMatch(c -> (c >= 0x80 && c <= 0x9F) || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069))) {
                    throw new IllegalArgumentException("body must not contain direction-control characters");
                }
                effectiveDate(p.effectiveDate());
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

    public static LegalSlug legalSlug(String raw) {
        try {
            return LegalSlug.valueOf(raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("legalSlug must be one of TERMS, PRIVACY");
        }
    }

    /** @return the date, or null when absent. @throws IllegalArgumentException anything but a real calendar date as yyyy-MM-dd */
    public static java.time.LocalDate effectiveDate(String raw) {
        if (raw == null) return null;
        try {
            if (!ISO_DATE.matcher(raw).matches()) throw new java.time.format.DateTimeParseException("shape", raw, 0);
            return java.time.LocalDate.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("effectiveDate must be a calendar date as yyyy-MM-dd");
        }
    }

    /**
     * True when two PUBLISHED windows share any instant (half-open {@code [startsAt, endsAt)}, null = unbounded), so a
     * successor may start exactly when its predecessor ends.
     */
    public static boolean windowsOverlap(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        boolean aStartsBeforeBEnds = aStart == null || bEnd == null || aStart.isBefore(bEnd);
        boolean bStartsBeforeAEnds = bStart == null || aEnd == null || bStart.isBefore(aEnd);
        return aStartsBeforeBEnds && bStartsBeforeAEnds;
    }

    /** Plain text: trimmed, bounded, no control characters (a newline only where allowed), no markup brackets. */
    private static void plainText(String s, int max, boolean newlines, String what) {
        if (s == null || s.isBlank() || s.length() > max || !s.equals(s.strip())
                || s.chars().anyMatch(c -> (c < 0x20 && !(newlines && c == '\n')) || c == 0x7F || c == '<' || c == '>')) {
            throw new IllegalArgumentException(what + " must be 1.." + max + " chars of plain text (no markup)");
        }
    }

    /**
     * Plain text shown on customer screens or read by screen readers: {@link #plainText} plus no C1 controls and no
     * invisible format characters (bidi overrides such as U+202E, zero-width joiners/spaces), which could reorder or hide
     * what a customer sees.
     */
    private static void displayText(String s, int max, String what) {
        plainText(s, max, false, what);
        if (s.codePoints().anyMatch(c -> (c >= 0x80 && c <= 0x9F) || Character.getType(c) == Character.FORMAT)) {
            throw new IllegalArgumentException(what + " must not contain invisible or direction-control characters");
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
