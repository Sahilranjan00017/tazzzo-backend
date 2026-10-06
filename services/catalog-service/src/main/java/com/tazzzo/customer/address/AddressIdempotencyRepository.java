package com.tazzzo.customer.address;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/**
 * {@code customer_address_idempotency}: one row per (customer, Idempotency-Key) that created an address. The key itself
 * is never stored, only its SHA-256 digest inside {@code _id} ({@code <customerId>|<digest>}), so the namespace is per
 * customer by construction. {@code requestHash} is the digest of the NORMALISED create request; {@code addressId} the
 * address it created. Rows live {@link #RETENTION} ({@code expire_at}, TTL index from V0015); the window only has to
 * outlast a client's retry of one ambiguous request.
 */
@Component
public class AddressIdempotencyRepository {

    public static final String COLLECTION = "customer_address_idempotency";
    static final Duration RETENTION = Duration.ofHours(24);

    private final MongoDatabase db;

    public AddressIdempotencyRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> rows() {
        return db.getCollection(COLLECTION);
    }

    static String id(String customerId, String idempotencyKey) {
        return customerId + "|" + sha256Hex(idempotencyKey);
    }

    Document find(ClientSession session, String id) {
        return rows().find(session, Filters.eq("_id", id)).first();
    }

    Document find(String id) {
        return rows().find(Filters.eq("_id", id)).first();
    }

    void delete(ClientSession session, String id) {
        rows().deleteOne(session, Filters.eq("_id", id));
    }

    /** A plain insert on {@code _id}: a concurrent same-key create collides here (write conflict or duplicate key). */
    void insert(ClientSession session, String id, String customerId, String requestHash, String addressId, Instant now) {
        rows().insertOne(session, new Document("_id", id)
                .append("customer_id", customerId)
                .append("request_hash", requestHash)
                .append("address_id", addressId)
                .append("created_at", Date.from(now))
                .append("expire_at", Date.from(now.plus(RETENTION))));
    }

    /** Account erasure: every row of one customer (wired into the erasure orchestrator at merge time). */

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
