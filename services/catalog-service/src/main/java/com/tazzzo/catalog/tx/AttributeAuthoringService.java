package com.tazzzo.catalog.tx;

import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.repo.ReleaseGate;
import com.tazzzo.catalog.repo.WritePath;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 2 — attribute AUTHORING. The hard distinctions this service maintains:
 * definition (what an attribute IS) ≠ key ≠ value ≠ schema membership ≠ product value.
 *
 * Versioning model: authoring inside an open release creates PENDING version documents;
 * the release's activation transaction (TaxonomyChangeService.activateRelease) flips them
 * active and supersedes prior versions ATOMICALLY — a crash can never leave a partially
 * active schema. Version documents are immutable; history is never rewritten (H-9).
 * Enum VALUE additions are additive data and need no release at all.
 */
@Service
public class AttributeAuthoringService {

    private static final Set<String> TYPES = Set.of("string", "number", "boolean", "enum_open");
    private static final Set<String> GOVERNANCE = Set.of("descriptive", "claim", "merchandising");

    private final Tx tx;
    private final WritePath writePath;
    private final ReleaseGate releaseGate;

    public AttributeAuthoringService(Tx tx, WritePath writePath, ReleaseGate releaseGate) {
        this.tx = tx;
        this.writePath = writePath;
        this.releaseGate = releaseGate;
    }

    /** New definition version (v1 for a new key). Type changes are forbidden (H-9): a type
     *  change is a NEW semantic key, never a new version of the old one. */
    public int createDefinition(String key, String type, String governance, List<String> knownValues) {
        if (!TYPES.contains(type)) {
            throw new TaxonomyChangeException("INVALID_TYPE", "unknown attribute type: " + type);
        }
        if (!GOVERNANCE.contains(governance)) {
            throw new TaxonomyChangeException("INVALID_GOVERNANCE", governance);
        }
        if ("merchandising".equals(governance)) {
            // Contract C4 immune system: merchandising intent is refused at definition review
            throw new TaxonomyChangeException("MERCHANDISING_REFUSED",
                    "merchandising groupings are collections, not attributes");
        }
        int[] version = new int[1];
        tx.run(session -> {
            EventPayload e = ev("ATTR_DEF_CREATED", Map.of("key", key, "type", type));
            String rel = releaseGate.requireOpen(session, e);
            Document latest = latestAny(session, "attribute_definitions",
                    Filters.eq("key", key));
            if (latest != null && !type.equals(latest.getString("type"))) {
                throw new TaxonomyChangeException("TYPE_CHANGE_FORBIDDEN",
                        key + " is " + latest.getString("type")
                                + "; a type change requires a NEW semantic key");
            }
            version[0] = latest == null ? 1 : latest.getInteger("version") + 1;
            Document doc = new Document("key", key).append("version", version[0])
                    .append("type", type).append("governance", governance)
                    .append("status", "pending").append("release_id", rel)
                    .append("created_at", new Date());
            if ("enum_open".equals(type)) {
                // H-11 monotonicity: known_values are a grow-only registry — a new version
                // CARRIES FORWARD the accumulated values and unions any new ones.
                java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
                if (latest != null && latest.getList("known_values", String.class) != null) {
                    union.addAll(latest.getList("known_values", String.class));
                }
                if (knownValues != null) union.addAll(knownValues);
                doc.append("known_values", new ArrayList<>(union));
            }
            try {
                writePath.insertWithEvent(session, "attribute_definitions", doc, e);
            } catch (MongoWriteException ex) {
                if (ex.getError().getCode() == 11000) {
                    throw new TaxonomyChangeException("DUPLICATE_DEFINITION",
                            key + "@" + version[0]);
                }
                throw ex;
            }
        });
        return version[0];
    }

    /** Additive enum value — data only, no release, no schema change (growth law). */
    public void addEnumValue(String key, String value) {
        tx.run(session -> {
            EventPayload e = ev("ATTR_ENUM_VALUE_ADDED", Map.of("key", key, "value", value));
            Document def = latestActive(session, "attribute_definitions", Filters.eq("key", key));
            if (def == null) {
                throw new TaxonomyChangeException("UNKNOWN_DEFINITION", key);
            }
            if (!"enum_open".equals(def.getString("type"))) {
                throw new TaxonomyChangeException("ENUM_ONLY",
                        key + " is " + def.getString("type"));
            }
            // monotone across versions: grow the active doc AND any pending successor,
            // so values acknowledged during a release window survive activation.
            writePath.auxWrite(session, "attribute_definitions", e, c -> c.updateMany(session,
                    Filters.and(Filters.eq("key", key),
                            Filters.gte("version", def.getInteger("version"))),
                    Updates.addToSet("known_values", value)));
        });
    }

    /**
     * New schema version adding a field. Making the field REQUIRED demands the explicit
     * allowBreaking acknowledgment (proof 3): compatibility debt is stamped onto affected
     * products at activation — never created silently.
     */
    public int addSchemaField(String schemaId, String key, boolean required, boolean allowBreaking) {
        int[] version = new int[1];
        tx.run(session -> {
            EventPayload e = ev("ATTR_SCHEMA_FIELD_ADDED",
                    Map.of("schema", schemaId, "key", key, "required", required));
            String rel = releaseGate.requireOpen(session, e);
            Document defActive = latestActive(session, "attribute_definitions", Filters.eq("key", key));
            Document defPending = latestAny(session, "attribute_definitions",
                    Filters.and(Filters.eq("key", key), Filters.eq("release_id", rel),
                            Filters.eq("status", "pending")));
            if (defActive == null && defPending == null) {
                throw new TaxonomyChangeException("UNKNOWN_DEFINITION",
                        key + " has no active or in-release pending definition");
            }
            // pending-aware: a second field in the SAME release amends the pending draft
            // (pending docs are drafts; immutability begins at activation).
            Document pendingSchema = latestAny(session, "attribute_schemas",
                    Filters.and(Filters.eq("schema_id", schemaId),
                            Filters.eq("release_id", rel), Filters.eq("status", "pending")));
            Document base = pendingSchema != null ? pendingSchema
                    : latestActive(session, "attribute_schemas", Filters.eq("schema_id", schemaId));
            if (base == null) {
                throw new TaxonomyChangeException("UNKNOWN_SCHEMA", schemaId);
            }
            List<Document> fields = new ArrayList<>(base.getList("fields", Document.class));
            if (fields.stream().anyMatch(f -> key.equals(f.getString("key")))) {
                throw new TaxonomyChangeException("DUPLICATE_FIELD",
                        key + " already in schema " + schemaId);
            }
            if (required && !allowBreaking) {
                throw new TaxonomyChangeException("REQUIRED_NEEDS_BACKFILL",
                        "adding REQUIRED '" + key + "' breaks existing products; pass "
                                + "allowBreaking to acknowledge the revalidation campaign");
            }
            fields.add(new Document("key", key).append("required", required));
            if (pendingSchema != null) {
                version[0] = pendingSchema.getInteger("version");
                org.bson.conversions.Bson upd = required
                        ? Updates.combine(Updates.set("fields", fields),
                                Updates.set("compat_breaking", true))
                        : Updates.set("fields", fields);
                writePath.auxWrite(session, "attribute_schemas", e, c -> c.updateOne(session,
                        Filters.and(Filters.eq("schema_id", schemaId),
                                Filters.eq("version", version[0]), Filters.eq("status", "pending")),
                        upd));
                return;
            }
            version[0] = base.getInteger("version") + 1;
            Document doc = new Document("schema_id", schemaId).append("version", version[0])
                    .append("scope", base.get("scope"))
                    .append("fields", fields)
                    .append("status", "pending").append("release_id", rel)
                    .append("compat_breaking", required)
                    .append("created_at", new Date());
            try {
                writePath.insertWithEvent(session, "attribute_schemas", doc, e);
            } catch (MongoWriteException ex) {
                if (ex.getError().getCode() == 11000) {
                    throw new TaxonomyChangeException("DUPLICATE_SCHEMA_VERSION",
                            schemaId + "@" + version[0]);
                }
                throw ex;
            }
        });
        return version[0];
    }

    /** Read helpers so transport never queries Mongo directly (review M2). */
    public Document activeDefinition(String key) {
        return latestActiveIn(writePath.database(), null, "attribute_definitions", Filters.eq("key", key));
    }

    public Document activeSchema(String schemaId) {
        return latestActiveIn(writePath.database(), null, "attribute_schemas",
                Filters.eq("schema_id", schemaId));
    }

    // ---- shared resolution helpers (also used by governance/activation) ----

    public static Document latestActiveIn(com.mongodb.client.MongoDatabase db, ClientSession session,
                                   String collection, org.bson.conversions.Bson filter) {
        var find = session == null ? db.getCollection(collection).find(
                Filters.and(filter, activeOrLegacy()))
                : db.getCollection(collection).find(session, Filters.and(filter, activeOrLegacy()));
        return find.sort(Sorts.descending("version")).first();
    }

    /**
     * Seed docs predate the status field: absent status counts as active (legacy-active). Public
     * because the consumer projector's page-level definition read (PHASE-5-BATCH-1) must apply the
     * SAME active-or-legacy meaning as {@link #latestActiveIn} — one definition of "active", not a
     * copy that could drift.
     */
    public static org.bson.conversions.Bson activeOrLegacy() {
        return Filters.or(Filters.eq("status", "active"), Filters.exists("status", false));
    }

    private Document latestActive(ClientSession session, String collection,
                                  org.bson.conversions.Bson filter) {
        return latestActiveIn(writePath.database(), session, collection, filter);
    }

    private Document latestAny(ClientSession session, String collection,
                               org.bson.conversions.Bson filter) {
        return writePath.database().getCollection(collection).find(session, filter)
                .sort(Sorts.descending("version")).first();
    }

    private EventPayload ev(String type, Map<String, Object> detail) {
        return new EventPayload(type, "TZP-SYSTEM", detail);
    }
}
