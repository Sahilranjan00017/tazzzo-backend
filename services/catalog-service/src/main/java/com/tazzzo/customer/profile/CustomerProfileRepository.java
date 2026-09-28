package com.tazzzo.customer.profile;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PR-12A — {@code customer_profiles}, one document per {@code customerId} (used directly as
 * {@code _id} — no separate customerId index, no duplicate customer/profile mapping). Carries ONLY
 * profile-owned fields: {@code displayName}, {@code email}, {@code createdAt}, {@code updatedAt},
 * {@code version}. Never {@code phoneNormalized}, session state, or anything auth owns.
 *
 * <p><b>Optimistic concurrency, one atomic write.</b> {@link #patch} is a single
 * {@code findOneAndUpdate} filtered on {@code {_id, version: expectedVersion}} — never a
 * read-modify-write. {@code expectedVersion == 0} means "no profile exists yet" and additionally
 * sets {@code upsert=true}: MongoDB folds the filter's equality fields into a freshly-inserted
 * document when none matches, so a legitimate first-time create and a version-precondition check
 * are the SAME atomic operation. A losing concurrent create (or a stale non-zero expected version)
 * both surface as either "no matching document" (upsert=false path) or a duplicate-key error on
 * {@code _id} (upsert=true path, two callers racing to create) — both are normalized to a single
 * {@code null} return so the caller never needs to distinguish them; either way it is a precondition
 * failure, never a silent overwrite.
 */
@Component
public class CustomerProfileRepository {

    public static final String COLLECTION = "customer_profiles";

    private final MongoDatabase db;

    public CustomerProfileRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public Document findById(String customerId) {
        return collection().find(Filters.eq("_id", customerId)).first();
    }

    /** @return the document AFTER the update, or {@code null} if {@code expectedVersion} did not
     *          match (stale precondition, OR a lost create-race when {@code expectedVersion == 0}) */
    public Document patch(String customerId, long expectedVersion, PatchField<String> displayName,
                          PatchField<String> email, Instant now) {
        List<Bson> updates = new ArrayList<>();
        if (displayName.isPresent()) {
            updates.add(Updates.set("displayName", displayName.value()));
        }
        if (email.isPresent()) {
            updates.add(Updates.set("email", email.value()));
        }
        updates.add(Updates.set("updatedAt", Date.from(now)));
        updates.add(Updates.setOnInsert("createdAt", Date.from(now)));
        updates.add(Updates.inc("version", 1L));

        boolean creating = expectedVersion == 0;
        try {
            return collection().findOneAndUpdate(
                    Filters.and(Filters.eq("_id", customerId), Filters.eq("version", expectedVersion)),
                    Updates.combine(updates),
                    new FindOneAndUpdateOptions().upsert(creating).returnDocument(ReturnDocument.AFTER));
        } catch (MongoCommandException e) {
            // findOneAndUpdate (a findAndModify command) reports a duplicate-key collision as a
            // single command-error response -- NOT the MongoWriteException a plain insertOne/
            // updateOne would raise (the idiom used elsewhere in this codebase, e.g. MintService).
            if (e.getErrorCode() == 11000) {
                // Lost a concurrent create race on _id -- treated identically to a stale version.
                return null;
            }
            throw e;
        }
    }
}
