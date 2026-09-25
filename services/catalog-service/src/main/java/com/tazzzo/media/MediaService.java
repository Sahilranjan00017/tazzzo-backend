package com.tazzzo.media;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.WritePath;
import com.tazzzo.catalog.tx.Tx;
import com.tazzzo.commerce.contract.ImageRole;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Production Media foundation (PR-05). Owns canonical media REFERENCES per
 * {@code (owner_type, owner_id)} in the {@code media_refs} collection. References only — no
 * upload, no processing, no S3/CDN provisioning, no public endpoint, no Spring wiring yet
 * (same policy as Pricing/Inventory: the first PR that needs a bean adds scanning).
 *
 * <p><b>Whole-set atomic replacement:</b> a write replaces the complete ordered asset array in
 * one document update inside one transaction — set-level invariants (single PRIMARY, unique
 * ordering) can never be observed half-applied.
 *
 * <p><b>Audit seam:</b> the C-4 {@link EventPayload} requires a productId. For
 * {@code ownerType=PRODUCT} the ownerId IS the productId; for {@code ownerType=SKU} the skuId is
 * passed (== productId at launch). This is audit-infrastructure coupling, NOT media identity —
 * media keys stay {@code (owner_type, owner_id)} and never change when variants diverge.
 *
 * <p><b>Duplicate delivery (ADR-015):</b> CAS + the unique owner key make duplicates
 * mutation-safe (second delivery conflicts; version never double-increments; no audit residue
 * survives rollback) — NOT return-original-result idempotency. An {@code idempotencyKey} +
 * dedupe store is REQUIRED in the PR that first exposes media writes on HTTP/CMS/queue/worker
 * transports.
 *
 * <p><b>Observability hooks:</b> media_write_success, media_write_validation_failure,
 * media_write_conflict, media_read_missing, media_read_inactive (structured logs; the
 * media_url_resolution_failure hook lives in {@link MediaUrlResolver}).
 */
public class MediaService implements MediaReadPort {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    static final String COLLECTION = "media_refs";

    private final Tx tx;
    private final WritePath writePath;
    private final MongoDatabase db;
    private final Clock clock;

    public MediaService(Tx tx, WritePath writePath, Clock clock) {
        this.tx = Objects.requireNonNull(tx);
        this.writePath = Objects.requireNonNull(writePath);
        this.db = writePath.database();
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Atomically create or CAS-replace the owner's complete canonical media set.
     *
     * @return the new version.
     */
    public long upsertMediaSet(UpsertMediaSetCommand cmd) {
        MediaSet validated = validateCommand(cmd);
        long newVersion;
        try {
            newVersion = (cmd.expectedVersion() == null) ? 1L : Math.addExact(cmd.expectedVersion(), 1);
        } catch (ArithmeticException e) {
            throw new InvalidMediaException("expectedVersion overflow: " + cmd.expectedVersion());
        }
        Date now = Date.from(clock.instant());
        EventPayload event = new EventPayload("MEDIA_SET_UPDATED", cmd.ownerId(),
                auditDetail(cmd, newVersion));

        try {
            tx.run(session -> {
                if (cmd.expectedVersion() == null) {
                    writePath.auxWrite(session, COLLECTION, event, c -> c.insertOne(session,
                            new Document("owner_type", cmd.ownerType().name())
                                    .append("owner_id", cmd.ownerId())
                                    .append("version", newVersion)
                                    .append("active", true)
                                    .append("assets", assetDocs(validated.assets()))
                                    .append("source", cmd.source())
                                    .append("created_at", now)
                                    .append("updated_at", now)));
                } else {
                    UpdateResult[] r = new UpdateResult[1];
                    writePath.auxWrite(session, COLLECTION, event, c -> r[0] = c.updateOne(session,
                            Filters.and(ownerFilter(cmd.ownerType(), cmd.ownerId()),
                                    Filters.eq("version", cmd.expectedVersion())),
                            Updates.combine(
                                    Updates.set("assets", assetDocs(validated.assets())),
                                    Updates.set("version", newVersion),
                                    Updates.set("source", cmd.source()),
                                    Updates.set("updated_at", now))));
                    if (r[0].getModifiedCount() == 0) {
                        Document existing = db.getCollection(COLLECTION)
                                .find(session, ownerFilter(cmd.ownerType(), cmd.ownerId())).first();
                        if (existing == null) {
                            throw new MediaNotFoundException("no media set for "
                                    + cmd.ownerType() + "/" + cmd.ownerId());
                        }
                        throw new MediaConflictException("stale update for " + cmd.ownerType() + "/"
                                + cmd.ownerId() + " expectedVersion=" + cmd.expectedVersion());
                    }
                }
            });
        } catch (MediaConflictException e) {
            log.info("media_write_conflict owner={}/{} reason=stale_version expected={}",
                    cmd.ownerType(), cmd.ownerId(), cmd.expectedVersion());
            throw e;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                log.info("media_write_conflict owner={}/{} reason=duplicate_create",
                        cmd.ownerType(), cmd.ownerId());
                throw new MediaConflictException("media set already exists for " + cmd.ownerType()
                        + "/" + cmd.ownerId() + "; use expectedVersion to update");
            }
            throw e;
        }
        log.info("media_write_success owner={}/{} version={} assets={}",
                cmd.ownerType(), cmd.ownerId(), newVersion, validated.assets().size());
        return newVersion;
    }

    @Override
    public MediaLookup findMedia(MediaOwnerType ownerType, String ownerId) {
        Objects.requireNonNull(ownerType, "ownerType required");
        if (ownerId == null || ownerId.isBlank()) {
            throw new InvalidMediaException("ownerId required");
        }
        Document d = db.getCollection(COLLECTION).find(ownerFilter(ownerType, ownerId)).first();
        if (d == null) {
            log.debug("media_read_missing owner={}/{}", ownerType, ownerId);
            return MediaLookup.missing();
        }
        MediaSet set = fromDocument(ownerType, ownerId, d);
        if (!set.active()) {
            log.debug("media_read_inactive owner={}/{}", ownerType, ownerId);
            return MediaLookup.of(MediaLookup.Status.INACTIVE, set);
        }
        return MediaLookup.of(MediaLookup.Status.PRESENT, set);
    }

    // --- validation --------------------------------------------------------------

    /** Validates the command by constructing the domain MediaSet (all invariants live there). */
    static MediaSet validateCommand(UpsertMediaSetCommand cmd) {
        try {
            Objects.requireNonNull(cmd, "command required");
            if (cmd.expectedVersion() != null && cmd.expectedVersion() < 1) {
                throw new InvalidMediaException("expectedVersion must be positive: " + cmd.expectedVersion());
            }
            long version = (cmd.expectedVersion() == null) ? 1L : cmd.expectedVersion();
            return new MediaSet(cmd.ownerType(), cmd.ownerId(), version, true,
                    cmd.assets() == null ? null : cmd.assets());
        } catch (InvalidMediaException e) {
            log.info("media_write_validation_failure owner={} reason={}", safeOwner(cmd), e.getMessage());
            throw e;
        } catch (IllegalArgumentException | NullPointerException e) {
            log.info("media_write_validation_failure owner={} reason={}", safeOwner(cmd), e.getMessage());
            throw new InvalidMediaException(e.getMessage());
        }
    }

    // --- document mapping ----------------------------------------------------------

    private static Bson ownerFilter(MediaOwnerType type, String id) {
        return Filters.and(Filters.eq("owner_type", type.name()), Filters.eq("owner_id", id));
    }

    private static List<Document> assetDocs(List<MediaAsset> assets) {
        List<Document> docs = new ArrayList<>(assets.size());
        for (MediaAsset a : assets) {
            docs.add(new Document("asset_id", a.assetId())
                    .append("asset_key", a.assetKey())
                    .append("role", a.role().name())
                    .append("sort_order", a.sortOrder())
                    .append("alt_text", a.altText())
                    .append("width", a.width())
                    .append("height", a.height())
                    .append("content_type", a.contentType()));
        }
        return docs;
    }

    private static MediaSet fromDocument(MediaOwnerType ownerType, String ownerId, Document d) {
        List<Document> assetDocs = d.getList("assets", Document.class, List.of());
        List<MediaAsset> assets = new ArrayList<>(assetDocs.size());
        for (Document a : assetDocs) {
            assets.add(new MediaAsset(
                    a.getString("asset_id"), a.getString("asset_key"),
                    ImageRole.valueOf(a.getString("role")),
                    ((Number) a.get("sort_order")).intValue(),
                    a.getString("alt_text"),
                    a.getInteger("width"), a.getInteger("height"),
                    a.getString("content_type")));
        }
        return new MediaSet(ownerType, ownerId,
                ((Number) d.get("version")).longValue(), d.getBoolean("active", false), assets);
    }

    private static Map<String, Object> auditDetail(UpsertMediaSetCommand cmd, long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("owner_type", cmd.ownerType() == null ? "?" : cmd.ownerType().name());
        m.put("owner_id", cmd.ownerId());
        m.put("asset_count", cmd.assets() == null ? 0 : cmd.assets().size());
        m.put("version", version);
        return m;
    }

    private static String safeOwner(UpsertMediaSetCommand cmd) {
        return cmd == null ? "?" : (cmd.ownerType() + "/" + cmd.ownerId());
    }
}
