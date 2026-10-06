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

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETED = "DELETED";
    static final String TOMBSTONE_PHONE_PREFIX = "deleted:";

    /**
     * Account deletion: the row becomes a tombstone in the caller's transaction. The phone (the only personal datum
     * here, and the login identity) is replaced by a value unique to this customer id, so the unique
     * {@code customer_one_per_phone} index holds and the same phone may register again as a NEW customer; the opaque
     * customer id itself is kept, because durable commercial records (orders) are keyed by it. Conditional on the row
     * still being ACTIVE, so two concurrent deletions cannot both "win": the loser sees no match and reads DELETED.
     *
     * @return the phone the row carried before the tombstone, or empty when the row was not ACTIVE (already deleted
     *         or unknown)
     */
    public java.util.Optional<String> tombstone(ClientSession session, String customerId, Instant now) {
        Document before = collection().findOneAndUpdate(session,
                Filters.and(Filters.eq("_id", customerId), Filters.eq("status", STATUS_ACTIVE)),
                Updates.combine(
                        Updates.set("status", STATUS_DELETED),
                        Updates.set("phoneNormalized", TOMBSTONE_PHONE_PREFIX + customerId),
                        Updates.set("deletedAt", now),
                        Updates.set("updatedAt", now),
                        Updates.unset("lastLoginAt")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));
        return before == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(before.getString("phoneNormalized"));
    }

    public Document findById(String customerId) {
        return collection().find(Filters.eq("_id", customerId)).first();
    }

    /** Session-scoped read -- for a caller that must observe this existence check as part of its
     *  OWN multi-document transaction (e.g. {@code customer.profile}'s identity-integrity check). */
    public Document findById(ClientSession session, String customerId) {
        return collection().find(session, Filters.eq("_id", customerId)).first();
    }
}
