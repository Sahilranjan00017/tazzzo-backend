package com.tazzzo.auth.session;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.tazzzo.auth.otp.Phone;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * PR-11C — the minimal customer identity record login requires. Canonical identity is
 * {@code phoneNormalized} (the SAME {@link Phone} rules PR-11B established); a customer document
 * carries NO profile fields (name/email/addresses/preferences — those belong to a future PR).
 *
 * <p><b>Race-safe resolve-or-create</b>: {@link #resolveOrCreate} is a single atomic
 * {@code findOneAndUpdate(upsert=true)} against the unique index on {@code phoneNormalized}. Mongo
 * itself serializes concurrent upserts racing on the same key — internally, if two upserts for the
 * same phone race, the server detects the duplicate-key condition from its own insert attempt and
 * automatically retries the loser as a plain update against the document the winner just created.
 * The caller therefore always gets back ONE consistent document, and {@code $setOnInsert} fields
 * (the candidate id/createdAt) are silently discarded on the losing/existing-customer path — an
 * existing customer's real id is always what is returned, never a fresh id nobody asked for.
 */
@Component
public class CustomerRepository {

    public static final String COLLECTION = "customers";

    private final MongoDatabase db;

    public CustomerRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    /**
     * @param candidateCustomerId used ONLY if no customer exists yet for this phone; discarded
     *                            (never a wasted id burned into a document) if one already does.
     */
    public Document resolveOrCreate(ClientSession session, Phone phone, Instant now, String candidateCustomerId) {
        return collection().findOneAndUpdate(session,
                Filters.eq("phoneNormalized", phone.value()),
                Updates.combine(
                        Updates.setOnInsert("_id", candidateCustomerId),
                        Updates.setOnInsert("phoneNormalized", phone.value()),
                        Updates.setOnInsert("status", "ACTIVE"),
                        Updates.setOnInsert("createdAt", now),
                        Updates.set("updatedAt", now),
                        Updates.set("lastLoginAt", now)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
    }

    public Document findById(String customerId) {
        return collection().find(Filters.eq("_id", customerId)).first();
    }
}
