package com.tazzzo.content;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.common.audit.Actor;
import com.tazzzo.common.audit.DomainAudit;
import com.tazzzo.common.audit.DomainEvent;
import com.tazzzo.media.InvalidMediaException;
import com.tazzzo.media.MediaIngestVerifier;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CMS content blocks and the app's operational configuration. Every admin write is validated by the domain, CAS-guarded on
 * {@code version}, and audited with the authenticated actor in the SAME transaction (a refused write leaves no audit row).
 * The public read ({@link #live}) only ever returns PUBLISHED blocks inside their time window, by the server clock.
 */
@Service
public class ContentService {

    static final String BLOCKS = "content_blocks";
    /** The app config is ONE document in the existing operational-config collection (no new collection). */
    static final String CONFIG = "system_config";
    static final String CONFIG_ID = "app_config";
    static final int MAX_BLOCKS_PER_PLACEMENT = 200;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(ContentService.class);

    private final MongoDatabase db;
    private final Tx tx;
    private final Clock clock;
    private final DomainAudit audit;
    private final MediaIngestVerifier media;

    /** Content image keys are issued under this prefix ({@code POST /api/v1/admin/content/uploads}). */
    public static final String CONTENT_KEY_PREFIX = "c/home/";

    public ContentService(MongoDatabase db, Tx tx, Clock clock, MediaIngestVerifier media) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.audit = new DomainAudit(db, clock);
        this.media = media;
    }

    private MongoCollection<Document> blocks() {
        return db.getCollection(BLOCKS);
    }

    // ---------------------------------------------------------------- blocks

    /** {@code audience} null = BOTH (an older client); targeting is HOME-only (D3). */
    public ContentBlock create(Actor actor, String placement, String type, String title, int sort, Instant startsAt, Instant endsAt,
                              ContentBlock.Payload payload, String audience) {
        Objects.requireNonNull(actor, "actor");
        ContentBlock.Placement pl = parse(ContentBlock.Placement.class, placement);
        ContentBlock.Type ty = parse(ContentBlock.Type.class, type);
        ContentBlock.Audience au;
        try {
            ContentBlock.requirePlacement(pl, ty);
            au = ContentBlock.audience(audience);
            ContentBlock.requireAudience(pl, au);
        } catch (IllegalArgumentException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, e.getMessage());
        }
        validate(ty, title, sort, startsAt, endsAt, payload);
        verifyNewImages(payload, null);
        if (blocks().countDocuments(Filters.and(Filters.eq("placement", pl.name()), Filters.ne("status", "ARCHIVED"))) >= MAX_BLOCKS_PER_PLACEMENT) {
            throw new ContentFailure(ContentFailure.Reason.STATE_CONFLICT, "too many blocks in this placement; archive some");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String id = newId();
        Document d = new Document("_id", id).append("placement", pl.name()).append("type", ty.name()).append("title", title)
                .append("sort", sort).append("status", ContentBlock.Status.DRAFT.name()).append("payload", payloadDoc(payload))
                .append("audience", au.name())
                .append("version", 1L).append("createdAt", Date.from(now)).append("updatedAt", Date.from(now))
                .append("createdBy", actor.id()).append("updatedBy", actor.id());
        if (startsAt != null) d.append("startsAt", Date.from(startsAt));
        if (endsAt != null) d.append("endsAt", Date.from(endsAt));
        tx.run(session -> {
            audit.append(session, new DomainEvent("content_block", id, "CONTENT_BLOCK_CREATED",
                    Map.of("type", ty.name(), "placement", pl.name(), "audience", au.name()), actor));
            blocks().insertOne(session, d);
        });
        return get(id);
    }

    /** {@code audience} null = keep the current one (an older client's PUT never erases targeting, multichannel §5.2). */
    public ContentBlock update(Actor actor, String id, long expectedVersion, String title, int sort, Instant startsAt, Instant endsAt,
                               ContentBlock.Payload payload, String audience) {
        Objects.requireNonNull(actor, "actor");
        ContentBlock current = get(id);
        if (current.status() == ContentBlock.Status.ARCHIVED) throw new ContentFailure(ContentFailure.Reason.STATE_CONFLICT, "an archived block is final");
        validate(current.type(), title, sort, startsAt, endsAt, payload);
        verifyNewImages(payload, current.payload());
        ContentBlock.Audience au;
        try {
            au = audience == null ? current.audience() : ContentBlock.audience(audience);
            ContentBlock.requireAudience(current.placement(), au);
        } catch (IllegalArgumentException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, e.getMessage());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> detail = au == current.audience() ? Map.of() : Map.of("audience", au.name(), "from", current.audience().name());
        List<Bson> changes = new ArrayList<>(List.of(
                Updates.set("title", title), Updates.set("sort", sort), Updates.set("payload", payloadDoc(payload)),
                startsAt == null ? Updates.unset("startsAt") : Updates.set("startsAt", Date.from(startsAt)),
                endsAt == null ? Updates.unset("endsAt") : Updates.set("endsAt", Date.from(endsAt)),
                Updates.set("updatedAt", Date.from(now))));
        // an older client's PUT (no audience) leaves the stored field as it is: a legacy document is not rewritten
        if (audience != null) changes.add(Updates.set("audience", au.name()));
        cas(actor, id, expectedVersion, List.of("DRAFT", "PUBLISHED"), "CONTENT_BLOCK_UPDATED", detail, Updates.combine(changes));
        return get(id);
    }

    /** {@code to} is PUBLISHED, DRAFT (unpublish) or ARCHIVED (final). */
    public ContentBlock setStatus(Actor actor, String id, long expectedVersion, String to) {
        Objects.requireNonNull(actor, "actor");
        ContentBlock.Status target = parse(ContentBlock.Status.class, to);
        ContentBlock current = get(id);
        if (current.status() == ContentBlock.Status.ARCHIVED || current.status() == target) {
            throw new ContentFailure(ContentFailure.Reason.STATE_CONFLICT, "the block is already " + current.status());
        }
        cas(actor, id, expectedVersion, List.of("DRAFT", "PUBLISHED"), "CONTENT_BLOCK_" + target.name(), Map.of("from", current.status().name()),
                Updates.combine(Updates.set("status", target.name()), Updates.set("updatedAt", Date.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)))));
        return get(id);
    }

    public ContentBlock get(String id) {
        Document d = id == null || !id.matches("CB_[A-Za-z0-9_-]{16,40}") ? null : blocks().find(Filters.eq("_id", id)).first();
        if (d == null) throw new ContentFailure(ContentFailure.Reason.NOT_FOUND, "no such block");
        return toBlock(d);
    }

    /** Admin list of one placement (bounded), optional status and audience, in display order. */
    public List<ContentBlock> list(String placement, String status, String audience) {
        ContentBlock.Placement pl = parse(ContentBlock.Placement.class, placement);
        List<Bson> f = new ArrayList<>(List.of(Filters.eq("placement", pl.name())));
        if (status != null) f.add(Filters.eq("status", parse(ContentBlock.Status.class, status).name()));
        if (audience != null) f.add(audienceFilter(parse(ContentBlock.Audience.class, audience)));
        List<ContentBlock> out = new ArrayList<>();
        for (Document d : blocks().find(Filters.and(f)).sort(Sorts.ascending("sort", "_id")).limit(MAX_BLOCKS_PER_PLACEMENT)) {
            out.add(toBlock(d));
        }
        return out;
    }

    /**
     * The public view: PUBLISHED blocks of the placement whose window contains "now" and whose audience admits
     * {@code channel}, in display order. {@code channel} null (a request without the parameter, as every client before
     * multichannel sent) sees BOTH only, so nothing targeted ever leaks to an unidentified platform; the filter is
     * authoritative here on the backend (D2), never on a client.
     */
    public List<ContentBlock> live(ContentBlock.Placement placement, ContentBlock.Channel channel) {
        Instant now = clock.instant();
        Date n = Date.from(now);
        List<ContentBlock> out = new ArrayList<>();
        for (Document d : blocks().find(Filters.and(Filters.eq("placement", placement.name()), Filters.eq("status", "PUBLISHED"),
                        Filters.or(Filters.exists("startsAt", false), Filters.lte("startsAt", n)),
                        Filters.or(Filters.exists("endsAt", false), Filters.gt("endsAt", n)),
                        channelFilter(channel)))
                .sort(Sorts.ascending("sort", "_id")).limit(MAX_BLOCKS_PER_PLACEMENT)) {
            ContentBlock b;
            try {
                b = toBlock(d);
            } catch (IllegalArgumentException e) {
                // one unreadable stored value (e.g. an audience outside the enum) hides that block, never the whole page
                log.warn("content_block_unreadable id={} reason={}", d.get("_id"), e.getMessage());
                continue;
            }
            // the same rules, applied twice: query and domain can never disagree
            if (b.isLiveAt(now) && b.audience().visibleTo(channel)) out.add(b);
        }
        return out;
    }

    /** Global content (HELP) as every client sees it. */
    public List<ContentBlock> live(ContentBlock.Placement placement) {
        return live(placement, null);
    }

    /**
     * A legacy document has no {@code audience} (or an explicit null) and means BOTH; {@code eq(field, null)} matches
     * both forms, exactly as {@link ContentBlock#audience(String)} reads them.
     */
    private static Bson audienceFilter(ContentBlock.Audience audience) {
        return audience == ContentBlock.Audience.BOTH
                ? Filters.or(Filters.eq("audience", null), Filters.eq("audience", audience.name()))
                : Filters.eq("audience", audience.name());
    }

    private static Bson channelFilter(ContentBlock.Channel channel) {
        List<String> admitted = new ArrayList<>(List.of(ContentBlock.Audience.BOTH.name()));
        if (channel == ContentBlock.Channel.APP) admitted.add(ContentBlock.Audience.APP_ONLY.name());
        if (channel == ContentBlock.Channel.WEB) admitted.add(ContentBlock.Audience.WEB_ONLY.name());
        return Filters.or(Filters.eq("audience", null), Filters.in("audience", admitted));
    }

    /**
     * The public help centre: live FAQ entries, by category (enum order) then display order. {@code category} null = all.
     * Bounded by the placement cap ({@value #MAX_BLOCKS_PER_PLACEMENT}).
     */
    public List<ContentBlock> liveFaqs(ContentBlock.FaqCategory category) {
        List<ContentBlock> out = new ArrayList<>();
        for (ContentBlock b : live(ContentBlock.Placement.HELP)) {
            if (b.type() != ContentBlock.Type.FAQ) continue;
            if (category != null && !category.name().equals(b.payload().faqCategory())) continue;
            out.add(b);
        }
        out.sort(java.util.Comparator.comparing((ContentBlock b) -> ContentBlock.FaqCategory.valueOf(b.payload().faqCategory()))
                .thenComparingInt(ContentBlock::sort).thenComparing(ContentBlock::blockId));
        return out;
    }

    /** One entry of a reorder: the block and the version the editor saw. */
    public record OrderEntry(String blockId, long expectedVersion) { }

    /**
     * Re-sequences a placement in one transaction: {@code order} must name every non-archived block of the placement exactly
     * once (a list that no longer matches, because another editor created or archived a block meanwhile, is a conflict),
     * each at the version the editor saw; block {@code i} gets {@code sort = (i + 1) * 10}. All or nothing: one stale
     * version refuses the whole reorder. Every moved block is audited.
     */
    public List<ContentBlock> reorder(Actor actor, String placement, List<OrderEntry> order) {
        Objects.requireNonNull(actor, "actor");
        ContentBlock.Placement pl = parse(ContentBlock.Placement.class, placement);
        if (order == null || order.isEmpty() || order.size() > MAX_BLOCKS_PER_PLACEMENT) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, "order must list 1.." + MAX_BLOCKS_PER_PLACEMENT + " blocks");
        }
        java.util.Set<String> requested = new java.util.HashSet<>();
        for (OrderEntry e : order) {
            if (e == null || e.blockId() == null || !requested.add(e.blockId())) {
                throw new ContentFailure(ContentFailure.Reason.INVALID, "order must name each block once");
            }
        }
        java.util.Set<String> current = new java.util.HashSet<>();
        for (Document d : blocks().find(Filters.and(Filters.eq("placement", pl.name()), Filters.ne("status", "ARCHIVED")))
                .projection(new Document("_id", 1))) {
            current.add(d.getString("_id"));
        }
        if (!current.equals(requested)) {
            throw new ContentFailure(ContentFailure.Reason.STALE_VERSION, "the placement's blocks changed; reload them");
        }
        Date now = Date.from(clock.instant().truncatedTo(ChronoUnit.MILLIS));
        tx.run(session -> {
            for (int i = 0; i < order.size(); i++) {
                OrderEntry e = order.get(i);
                int sort = (i + 1) * 10;
                audit.append(session, new DomainEvent("content_block", e.blockId(), "CONTENT_BLOCK_REORDERED",
                        new LinkedHashMap<>(Map.of("sort", sort)), actor));
                if (blocks().updateOne(session, Filters.and(Filters.eq("_id", e.blockId()), Filters.eq("placement", pl.name()),
                                Filters.eq("version", e.expectedVersion()), Filters.in("status", List.of("DRAFT", "PUBLISHED"))),
                        Updates.combine(Updates.set("sort", sort), Updates.set("updatedAt", now), Updates.set("updatedBy", actor.id()),
                                Updates.inc("version", 1L))).getModifiedCount() != 1) {
                    throw new ContentFailure(ContentFailure.Reason.STALE_VERSION, "a block changed; reload them");
                }
            }
        });
        return list(pl.name(), null, null).stream().filter(b -> b.status() != ContentBlock.Status.ARCHIVED).toList();
    }

    /**
     * What {@code channel} would see on {@code placement} at {@code at} (default now): the public selection rules, applied to
     * PUBLISHED blocks and, when {@code includeDrafts}, to DRAFT blocks as if they were published. Admin-only: drafts are
     * never reachable through the public API.
     */
    public List<ContentBlock> preview(ContentBlock.Placement placement, ContentBlock.Channel channel, Instant at, boolean includeDrafts) {
        Instant when = at == null ? clock.instant() : at;
        List<String> statuses = includeDrafts ? List.of("PUBLISHED", "DRAFT") : List.of("PUBLISHED");
        List<ContentBlock> out = new ArrayList<>();
        for (Document d : blocks().find(Filters.and(Filters.eq("placement", placement.name()), Filters.in("status", statuses),
                        channelFilter(channel)))
                .sort(Sorts.ascending("sort", "_id")).limit(MAX_BLOCKS_PER_PLACEMENT)) {
            ContentBlock b;
            try {
                b = toBlock(d);
            } catch (IllegalArgumentException e) {
                continue;
            }
            boolean inWindow = (b.startsAt() == null || !when.isBefore(b.startsAt())) && (b.endsAt() == null || when.isBefore(b.endsAt()));
            if (inWindow && b.audience().visibleTo(channel)) out.add(b);
        }
        return out;
    }

    /**
     * When storage is configured, an image key a banner newly references (not already on {@code before}) must have been
     * issued for content and must hold an allowed image in storage. Keys a block already carries are not re-checked.
     */
    private void verifyNewImages(ContentBlock.Payload payload, ContentBlock.Payload before) {
        if (media == null || !media.verifying()) return;
        for (String key : java.util.Arrays.asList(payload.imageAssetKey(), payload.desktopImageAssetKey())) {
            if (key == null) continue;
            if (before != null && (key.equals(before.imageAssetKey()) || key.equals(before.desktopImageAssetKey()))) continue;
            if (!key.startsWith(CONTENT_KEY_PREFIX)) {
                throw new ContentFailure(ContentFailure.Reason.INVALID, "image key was not uploaded for content");
            }
            try {
                media.verify(key, null);
            } catch (InvalidMediaException e) {
                throw new ContentFailure(ContentFailure.Reason.INVALID, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------- app config

    public AppConfig appConfig() {
        Document d = db.getCollection(CONFIG).find(Filters.eq("_id", CONFIG_ID)).first();
        if (d == null) return AppConfig.DEFAULT;
        return new AppConfig(d.getBoolean("storeOpen", true), d.getBoolean("maintenance", false), d.getString("maintenanceMessage"),
                d.getString("minAndroid"), d.getString("latestAndroid"), d.getString("minIos"), d.getString("latestIos"),
                d.getString("supportPhone"), d.getString("supportEmail"), d.getString("termsUrl"), d.getString("privacyUrl"),
                d.getString("refundPolicyUrl"), ((Number) d.get("version")).longValue());
    }

    /** Replace the whole config. {@code expectedVersion} 0 creates it; otherwise CAS. */
    public AppConfig putAppConfig(Actor actor, long expectedVersion, AppConfig next) {
        Objects.requireNonNull(actor, "actor");
        try {
            next.validate();
        } catch (IllegalArgumentException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, e.getMessage());
        }
        Document d = new Document("config_type", "app").append("storeOpen", next.storeOpen()).append("maintenance", next.maintenance())
                .append("maintenanceMessage", next.maintenanceMessage()).append("minAndroid", next.minAndroid())
                .append("latestAndroid", next.latestAndroid()).append("minIos", next.minIos()).append("latestIos", next.latestIos())
                .append("supportPhone", next.supportPhone()).append("supportEmail", next.supportEmail())
                .append("termsUrl", next.termsUrl()).append("privacyUrl", next.privacyUrl()).append("refundPolicyUrl", next.refundPolicyUrl())
                .append("version", expectedVersion + 1).append("updatedAt", Date.from(clock.instant()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("storeOpen", next.storeOpen());
        detail.put("maintenance", next.maintenance());
        detail.put("version", expectedVersion + 1);
        try {
            tx.run(session -> {
                audit.append(session, new DomainEvent("app_config", CONFIG_ID, "APP_CONFIG_UPDATED", detail, actor));
                if (expectedVersion == 0) {
                    db.getCollection(CONFIG).insertOne(session, d.append("_id", CONFIG_ID));
                } else if (db.getCollection(CONFIG).replaceOne(session, Filters.and(Filters.eq("_id", CONFIG_ID),
                        Filters.eq("version", expectedVersion)), d).getModifiedCount() != 1) {
                    throw new ContentFailure(ContentFailure.Reason.STALE_VERSION, "app config changed; reload it");
                }
            });
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) throw new ContentFailure(ContentFailure.Reason.STALE_VERSION, "app config already exists; reload it");
            throw e;
        }
        return appConfig();
    }

    // ----------------------------------------------------------------- helpers

    private void cas(Actor actor, String id, long expectedVersion, List<String> from, String eventType, Map<String, Object> detail, Bson update) {
        tx.run(session -> {
            audit.append(session, new DomainEvent("content_block", id, eventType, new LinkedHashMap<>(detail), actor));
            if (blocks().updateOne(session, Filters.and(Filters.eq("_id", id), Filters.eq("version", expectedVersion), Filters.in("status", from)),
                    Updates.combine(update, Updates.set("updatedBy", actor.id()), Updates.inc("version", 1L))).getModifiedCount() != 1) {
                throw new ContentFailure(ContentFailure.Reason.STALE_VERSION, "the block changed; reload it");
            }
        });
    }

    private static void validate(ContentBlock.Type type, String title, int sort, Instant startsAt, Instant endsAt, ContentBlock.Payload payload) {
        try {
            ContentBlock.validate(type, title, sort, startsAt, endsAt, payload);
        } catch (IllegalArgumentException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, e.getMessage());
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw new ContentFailure(ContentFailure.Reason.INVALID, "unknown " + type.getSimpleName());
        }
    }

    private static Document payloadDoc(ContentBlock.Payload p) {
        Document d = new Document();
        if (p.imageAssetKey() != null) d.append("imageAssetKey", p.imageAssetKey());
        if (p.link() != null) d.append("link", p.link());
        if (!p.ids().isEmpty()) d.append("ids", p.ids());
        if (p.faqCategory() != null) d.append("faqCategory", p.faqCategory());
        if (p.question() != null) d.append("question", p.question());
        if (p.answer() != null) d.append("answer", p.answer());
        if (p.subtitle() != null) d.append("subtitle", p.subtitle());
        if (p.altText() != null) d.append("altText", p.altText());
        if (p.desktopImageAssetKey() != null) d.append("desktopImageAssetKey", p.desktopImageAssetKey());
        return d;
    }

    private static ContentBlock toBlock(Document d) {
        Document p = d.get("payload", Document.class);
        return new ContentBlock(d.getString("_id"), ContentBlock.Placement.valueOf(d.getString("placement")),
                ContentBlock.Type.valueOf(d.getString("type")), d.getString("title"), d.getInteger("sort"),
                ContentBlock.Status.valueOf(d.getString("status")), d.getDate("startsAt") == null ? null : d.getDate("startsAt").toInstant(),
                d.getDate("endsAt") == null ? null : d.getDate("endsAt").toInstant(),
                new ContentBlock.Payload(p.getString("imageAssetKey"), p.getString("link"), p.getList("ids", String.class),
                        p.getString("faqCategory"), p.getString("question"), p.getString("answer"),
                        p.getString("subtitle"), p.getString("altText"), p.getString("desktopImageAssetKey")),
                ContentBlock.audience(d.getString("audience")),
                ((Number) d.get("version")).longValue(), d.getDate("createdAt").toInstant(), d.getDate("updatedAt").toInstant(),
                d.getString("createdBy"), d.getString("updatedBy"));
    }

    private static String newId() {
        byte[] b = new byte[15];
        RANDOM.nextBytes(b);
        return "CB_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
