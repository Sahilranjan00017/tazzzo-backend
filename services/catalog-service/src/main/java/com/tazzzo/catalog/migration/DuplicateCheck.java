package com.tazzzo.catalog.migration;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only duplicate detection that must come back EMPTY before a unique index may be created. It only
 * ever REPORTS duplicates (a bounded sample); it never deletes, merges or edits business data (R5).
 */
public final class DuplicateCheck {

    public static final int SAMPLE_LIMIT = 20;

    private final String collection;
    private final Bson match;
    private final List<String> fields;

    private DuplicateCheck(String collection, Bson match, List<String> fields) {
        this.collection = collection;
        this.match = match;
        this.fields = List.copyOf(fields);
    }

    /** Groups documents (optionally restricted by {@code match}, i.e. a partial filter) by {@code fields}. */
    public static DuplicateCheck byFields(String collection, Bson match, String... fields) {
        return new DuplicateCheck(collection, match, List.of(fields));
    }

    public String description() {
        return "no two documents in " + collection + (match == null ? "" : " matching " + match.toBsonDocument().toJson())
                + " share the same (" + String.join(", ", fields) + ")";
    }

    /** Up to {@link #SAMPLE_LIMIT} duplicate groups: each {@code {_id:{field:value...}, n:count}}. */
    public List<Document> sample(MongoDatabase db) {
        boolean exists = false;
        for (String c : db.listCollectionNames()) {
            if (c.equals(collection)) { exists = true; break; }
        }
        if (!exists) return List.of();
        Document id = new Document();
        for (String f : fields) id.append(f, "$" + f);
        List<Bson> pipeline = new ArrayList<>();
        if (match != null) pipeline.add(Aggregates.match(match));
        pipeline.add(Aggregates.group(id, Accumulators.sum("n", 1)));
        pipeline.add(Aggregates.match(Filters.gt("n", 1)));
        pipeline.add(Aggregates.limit(SAMPLE_LIMIT));
        return db.getCollection(collection).aggregate(pipeline).into(new ArrayList<>());
    }
}
