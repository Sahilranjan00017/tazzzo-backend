package com.tazzzo.catalog.repo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.events.EventPayload;
import com.mongodb.MongoClientSettings;
import com.tazzzo.catalog.tx.CasConflictException;
import com.tazzzo.catalog.tx.ImmutableFieldException;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Component;

/**
 * THE single write path (K. of the Implementation Contract).
 * C-3: the event is appended to product_events BEFORE the state write, same session.
 * C-4: an EventPayload is a required parameter — a mutation without an event does not compile.
 * No service mutates state through any other route.
 */
@Component
public class WritePath {

    /**
     * D-1b — fields the contract declares immutable after creation. Guarded here, at the single
     * write path, rather than in each service: no service writes these today, and this rail keeps
     * it that way. It is PREVENTIVE, not the fix for D-1 itself — the demonstrated bypass is a
     * direct MongoDB write that never reaches this class, and is closed operationally (D-1a,
     * Atlas roles). What this stops is a FUTURE service reopening the class of problem, in the
     * same spirit as C-4 making the audit event a compile-time requirement.
     *
     * NOTE for CAT-ID: `identity` is guarded as a subtree, so the canonical_key backfill cannot
     * write identity.canonical_key through casUpdateWithEvent. That backfill will need an
     * explicit, audited exception — deliberately not pre-granted here.
     */
    private static final java.util.Set<String> IMMUTABLE_ROOTS =
            java.util.Set.of("_id", "product_type", "identity");

    private final MongoDatabase db;

    public WritePath(MongoDatabase db) {
        this.db = db;
    }

    public void insertWithEvent(ClientSession session, String collection, Document doc, EventPayload event) {
        appendEvent(session, event);
        db.getCollection(collection).insertOne(session, doc);
    }

    /** CAS update guarded by expectedVersion; the update MUST $inc version itself. */
    public long casUpdateWithEvent(ClientSession session, String collection, String id,
                                   long expectedVersion, Bson update, EventPayload event) {
        // BEFORE appendEvent: a rejected update must not leave an audit event behind, even
        // though the surrounding transaction would roll it back.
        assertNoImmutableFieldWrites(update);
        appendEvent(session, event);
        UpdateResult r = db.getCollection(collection).updateOne(session,
                Filters.and(Filters.eq("_id", id), Filters.eq("version", (int) expectedVersion)), update);
        if (r.getModifiedCount() == 0) {
            throw new CasConflictException(collection + "/" + id + " expectedVersion=" + expectedVersion);
        }
        return r.getModifiedCount();
    }

    /**
     * CAT-ID-6 / WP-6 — the ONLY way identity.canonical_key is ever written after mint.
     *
     * This is deliberately NOT an exception to the D-1b rail. casUpdateWithEvent accepts an
     * arbitrary Bson update, which is exactly why it must keep refusing `identity` for every
     * caller, forever. This door is impoverished instead:
     *   - it takes NO update document, so it cannot express anything but the canonical key;
     *   - it is WRITE-ONCE: the filter requires canonical_key to be currently null, so a second
     *     run is a no-op rather than an overwrite (returns false, which is not an error);
     *   - it still appends an audit event (C-4), so no identity write is silent;
     *   - no controller path reaches it — the backfill worker is its only caller.
     *
     * Write-once is the load-bearing property: the backfill cannot corrupt identity even if run
     * twice, run concurrently, or run against a partially migrated corpus.
     *
     * @return true if this call set the key; false if it was already set (no-op).
     */
    public boolean setCanonicalKeyOnce(ClientSession session, String productId,
                                       String key, String keyVersion, EventPayload event) {
        appendEvent(session, event);
        UpdateResult r = db.getCollection("products").updateOne(session,
                Filters.and(Filters.eq("_id", productId),
                        Filters.or(Filters.eq("identity.canonical_key", null),
                                Filters.exists("identity.canonical_key", false))),
                new Document("$set", new Document("identity.canonical_key", key)
                        .append("identity.canonical_key_version", keyVersion)));
        return r.getModifiedCount() == 1;
    }

    /** Non-product auxiliary state (registries, queues, links) — still event-carrying. */
    public void auxWrite(ClientSession session, String collection, EventPayload event,
                         java.util.function.Consumer<com.mongodb.client.MongoCollection<Document>> write) {
        appendEvent(session, event);
        write.accept(db.getCollection(collection));
    }

    public MongoDatabase database() {
        return db;
    }

    /**
     * D-1b. Walks the update operators and refuses any that reach an immutable root — including
     * dotted paths (identity.type) and $rename TARGETS, not just its sources.
     */
    private void assertNoImmutableFieldWrites(Bson update) {
        BsonDocument doc = update.toBsonDocument(BsonDocument.class,
                MongoClientSettings.getDefaultCodecRegistry());
        for (java.util.Map.Entry<String, BsonValue> op : doc.entrySet()) {
            if (!op.getValue().isDocument()) continue;
            BsonDocument fields = op.getValue().asDocument();
            for (java.util.Map.Entry<String, BsonValue> f : fields.entrySet()) {
                reject(op.getKey(), f.getKey());
                if ("$rename".equals(op.getKey()) && f.getValue().isString()) {
                    reject(op.getKey(), f.getValue().asString().getValue()); // rename TARGET
                }
            }
        }
    }

    private void reject(String operator, String path) {
        String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        if (IMMUTABLE_ROOTS.contains(root)) {
            throw new ImmutableFieldException(
                    "field is immutable after creation: " + path + " (via " + operator + ")");
        }
    }

    private void appendEvent(ClientSession session, EventPayload event) {
        db.getCollection("product_events").insertOne(session,
                ProductDocuments.eventDoc(event.type(), event.productId(), event.detail()));
    }
}
