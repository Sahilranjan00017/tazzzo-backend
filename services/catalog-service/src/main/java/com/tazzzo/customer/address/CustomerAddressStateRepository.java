package com.tazzzo.customer.address;

import com.mongodb.MongoCommandException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

/**
 * PR-12B — {@code customer_address_state}, ONE document per customer: {@code addressCount} and
 * {@code defaultAddressId}. This single per-customer document is the ONE place both concurrency
 * invariants this domain needs are enforced, and it deliberately replaces the naive design of
 * storing {@code isDefault} as a field on each address document:
 *
 * <p><b>Why not store {@code isDefault} per-address?</b> Two address documents are two SEPARATE
 * Mongo documents — concurrent transactions clearing one and setting the other do not necessarily
 * conflict on the same document (a classic write-skew hazard under snapshot isolation), so
 * "exactly one default" would need extra machinery (a unique partial index, or careful ordering) to
 * guarantee. A single {@code defaultAddressId} POINTER field on ONE per-customer document makes
 * "at most one default" a STRUCTURAL invariant (a single field can only hold one value), and any
 * two concurrent "set default" calls for the same customer necessarily write the SAME document —
 * real MongoDB write-conflict detection then guarantees exactly one succeeds. {@code isDefault} on
 * a response is simply {@code addressId.equals(state.defaultAddressId)}, computed at read time.
 *
 * <p><b>Why the address-count limit lives here too:</b> the SAME write-conflict-on-one-document
 * property closes the "N concurrent creates at the limit" race. Counting address documents (a
 * COUNT query) can never do this — two concurrent creates could both observe count=9 and both
 * insert, exceeding the limit (also write-skew). Incrementing a bounded COUNTER FIELD on this one
 * document is a real atomic CAS: {@link #incrementIfBelowLimit} either commits an increment under
 * the limit or fails, with two concurrent callers guaranteed to conflict on this one document.
 */
@Component
public class CustomerAddressStateRepository {

    public static final String COLLECTION = "customer_address_state";

    private final MongoDatabase db;

    public CustomerAddressStateRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public Document findByCustomerId(String customerId) {
        return collection().find(Filters.eq("_id", customerId)).first();
    }

    public Document findByCustomerId(ClientSession session, String customerId) {
        return collection().find(session, Filters.eq("_id", customerId)).first();
    }

    /** @return the document AFTER incrementing, or {@code null} if the customer is already at
     *          {@code limit} active addresses (a lost race at the limit surfaces the SAME way, via
     *          a duplicate-key upsert collision -- see {@link CustomerProfileRepository}'s
     *          identical idiom in PR-12A). */
    public Document incrementIfBelowLimit(ClientSession session, String customerId, int limit, Instant now) {
        try {
            return collection().findOneAndUpdate(session,
                    Filters.and(Filters.eq("_id", customerId), Filters.lt("addressCount", limit)),
                    Updates.combine(Updates.inc("addressCount", 1L), Updates.set("updatedAt", Date.from(now))),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == 11000) {
                return null;
            }
            throw e;
        }
    }

    public void decrement(ClientSession session, String customerId, Instant now) {
        collection().updateOne(session, Filters.eq("_id", customerId),
                Updates.combine(Updates.inc("addressCount", -1L), Updates.set("updatedAt", Date.from(now))));
    }

    /** @param addressId the new default, or {@code null} to clear (no addresses remain). */
    public void setDefault(ClientSession session, String customerId, String addressId, Instant now) {
        collection().updateOne(session, Filters.eq("_id", customerId),
                Updates.combine(Updates.set("defaultAddressId", addressId), Updates.set("updatedAt", Date.from(now))),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }
}
