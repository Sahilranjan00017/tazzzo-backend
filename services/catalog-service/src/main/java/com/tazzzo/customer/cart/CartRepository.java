package com.tazzzo.customer.cart;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * PR-12C — {@code customer_carts}, ONE document per customer, {@code customerId} as {@code _id}
 * (no separate cart id, no extra index). The document is NEVER deleted by expiry or by clearing:
 * deleting it would reset the logical version to 0 and let a stale client re-create over newer
 * history. Every version-guarded write filters on BOTH {@code _id} and the expected
 * {@code version}. No Mongo TTL index exists on purpose (see SchemaBootstrap).
 */
@Component
public class CartRepository {

    public static final String COLLECTION = "customer_carts";

    private final MongoDatabase db;

    public CartRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public Document findById(String customerId) {
        return collection().find(Filters.eq("_id", customerId)).first();
    }

    public Document findById(ClientSession session, String customerId) {
        return collection().find(session, Filters.eq("_id", customerId)).first();
    }

    public void insert(ClientSession session, Document cart) {
        collection().insertOne(session, cart);
    }

    /** @return true if the version-guarded write matched (i.e. the caller's expected version is current). */
    public boolean replaceItemsIfVersion(ClientSession session, String customerId, long expectedVersion,
                                         List<Document> items, Instant now, Instant expiresAt) {
        return collection().updateOne(session,
                Filters.and(Filters.eq("_id", customerId), Filters.eq("version", expectedVersion)),
                Updates.combine(Updates.set("items", items), Updates.set("version", expectedVersion + 1),
                        Updates.set("updatedAt", Date.from(now)), Updates.set("expiresAt", Date.from(expiresAt))))
                .getModifiedCount() > 0;
    }

    /**
     * PR-15A-0 — purchase finalization CAS: clears the items ONLY if the live version is exactly the
     * purchased source version, advancing (never resetting) it, and raises
     * {@code purchasedThroughVersion} with {@code $max} in the same atomic update. Session-aware: it
     * commits or rolls back with the caller's transaction.
     *
     * @return true iff it applied
     */
    public boolean clearPurchasedIfVersion(ClientSession session, String customerId, long sourceVersion,
                                           Instant now, Instant newExpiresAt) {
        return collection().updateOne(session,
                Filters.and(Filters.eq("_id", customerId), Filters.eq("version", sourceVersion)),
                Updates.combine(Updates.set("items", List.of()), Updates.set("version", sourceVersion + 1),
                        Updates.set("updatedAt", Date.from(now)), Updates.set("expiresAt", Date.from(newExpiresAt)),
                        Updates.max(CartPurchaseService.MARKER, sourceVersion)))
                .getModifiedCount() > 0;
    }

    /**
     * PR-15A-0 — raises ONLY {@code purchasedThroughVersion} ({@code $max}, monotonic). Deliberately
     * touches no items, no version, no timestamps: a newer cart's content and its clients'
     * {@code If-Match} version are unaffected.
     *
     * @return true iff the cart document exists (an already-covered marker is a valid no-op)
     */
    public boolean markPurchasedThrough(ClientSession session, String customerId, long sourceVersion) {
        return collection().updateOne(session, Filters.eq("_id", customerId),
                Updates.max(CartPurchaseService.MARKER, sourceVersion)).getMatchedCount() > 0;
    }

    /** Housekeeping CAS used by GET: clears an EXPIRED cart (strictly past {@code expiresAt}: exactly 7 days is
     *  still kept), advancing (never resetting) the version. */
    public boolean clearExpiredIfVersion(String customerId, long seenVersion, Instant now, Instant newExpiresAt) {
        return collection().updateOne(
                Filters.and(Filters.eq("_id", customerId), Filters.eq("version", seenVersion),
                        Filters.lt("expiresAt", Date.from(now))),
                Updates.combine(Updates.set("items", List.of()), Updates.set("version", seenVersion + 1),
                        Updates.set("updatedAt", Date.from(now)), Updates.set("expiresAt", Date.from(newExpiresAt))))
                .getModifiedCount() > 0;
    }
}
