package com.tazzzo.catalog.repo;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import com.tazzzo.catalog.domain.ProductDocuments;
import com.tazzzo.catalog.events.EventPayload;
import com.tazzzo.catalog.tx.CasConflictException;
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
        appendEvent(session, event);
        UpdateResult r = db.getCollection(collection).updateOne(session,
                Filters.and(Filters.eq("_id", id), Filters.eq("version", (int) expectedVersion)), update);
        if (r.getModifiedCount() == 0) {
            throw new CasConflictException(collection + "/" + id + " expectedVersion=" + expectedVersion);
        }
        return r.getModifiedCount();
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

    private void appendEvent(ClientSession session, EventPayload event) {
        db.getCollection("product_events").insertOne(session,
                ProductDocuments.eventDoc(event.type(), event.productId(), event.detail()));
    }
}
