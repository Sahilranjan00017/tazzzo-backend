package com.tazzzo.content;

import com.tazzzo.media.MediaAsset;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One merchandising block of a customer screen (today: HOME). A block is authored as DRAFT, goes live only when PUBLISHED
 * and inside its optional time window, and is never deleted (ARCHIVED). The payload is validated per type with closed
 * grammars: links can only point at a product, a category or a search, never at an arbitrary URL.
 */
public record ContentBlock(String blockId, Placement placement, Type type, String title, int sort, Status status, Instant startsAt,
                           Instant endsAt, Payload payload, long version, Instant createdAt, Instant updatedAt) {

    public enum Placement { HOME }

    public enum Type { BANNER, PRODUCT_RAIL, CATEGORY_GRID }

    public enum Status { DRAFT, PUBLISHED, ARCHIVED }

    /** {@code imageAssetKey}/{@code link} for BANNER; {@code ids} (products or category nodes) for the rails/grids. */
    public record Payload(String imageAssetKey, String link, List<String> ids) {
        public Payload {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    public static final int MAX_TITLE = 80;
    public static final int MAX_RAIL = 20;
    public static final int MAX_GRID = 12;
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
