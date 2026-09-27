package com.tazzzo.auth.session;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.SessionId;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;

/**
 * PR-11C — raw-driver Mongo access for {@code customer_sessions}, following this repository's
 * established convention (no MongoTemplate/Spring Data — see {@code PricingService}/
 * {@code OtpChallengeRepository}): every state transition is an atomic
 * {@code updateOne}/{@code findOneAndUpdate} filtered on the EXPECTED current state, never a
 * read-then-write-back. No direct wall-clock read anywhere in this class — every timestamp is
 * supplied by the caller from the injected {@link java.time.Clock}.
 *
 * <p>Session expiry ({@code expiresAt}) and revocation ({@code revokedAt}) are APPLICATION
 * predicates, checked explicitly in every query here — a Mongo TTL index (added for cleanup only)
 * is never the authorization mechanism.
 */
@Component
public class CustomerSessionRepository {

    public static final String COLLECTION = "customer_sessions";

    private final MongoDatabase db;

    public CustomerSessionRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    public Document create(ClientSession session, SessionId sessionId, CustomerId customerId, Instant now,
                           Instant expiresAt, byte[] refreshDigest) {
        Document doc = newDocument(sessionId, customerId, now, expiresAt, refreshDigest);
        collection().insertOne(session, doc);
        return doc;
    }

    /** Non-transactional convenience overload — used by tests seeding a session directly. */
    public Document create(SessionId sessionId, CustomerId customerId, Instant now, Instant expiresAt,
                           byte[] refreshDigest) {
        Document doc = newDocument(sessionId, customerId, now, expiresAt, refreshDigest);
        collection().insertOne(doc);
        return doc;
    }

    private static Document newDocument(SessionId sessionId, CustomerId customerId, Instant now, Instant expiresAt,
                                        byte[] refreshDigest) {
        return new Document("_id", sessionId.value())
                .append("customerId", customerId.value())
                .append("createdAt", now)
                .append("expiresAt", expiresAt)
                .append("revokedAt", null)
                .append("refreshTokenDigest", Base64.getEncoder().encodeToString(refreshDigest))
                .append("refreshGeneration", 1)
                .append("lastRotatedAt", now);
    }

    public Document findById(String sessionId) {
        return collection().find(Filters.eq("_id", sessionId)).first();
    }

    /**
     * Atomic CAS refresh rotation: the filter requires the CURRENTLY-STORED digest to match the
     * PRESENTED token's digest — so of two concurrent requests presenting the SAME refresh token,
     * only the first to commit can possibly match on the second's attempt (the first already
     * changed the stored digest). Session-scoped: this is the sole replay defense required for this
     * PR (no cross-session token-family tracking) — a stale/rotated-away token simply never matches
     * the CURRENT digest again, hardening §8's "generic auth failure" for reuse.
     *
     * @return the post-update document on success, or {@code null} if the digest no longer matches
     *         (lost the race, or the token was already rotated/revoked/expired) — the caller must
     *         treat this as a generic auth failure, never distinguish why.
     */
    public Document tryRotateRefresh(ClientSession session, SessionId sessionId, String presentedDigestBase64,
                                     byte[] newDigest, Instant now) {
        return collection().findOneAndUpdate(session,
                Filters.and(Filters.eq("_id", sessionId.value()), Filters.eq("revokedAt", null),
                        Filters.gt("expiresAt", now), Filters.eq("refreshTokenDigest", presentedDigestBase64)),
                Updates.combine(Updates.set("refreshTokenDigest", Base64.getEncoder().encodeToString(newDigest)),
                        Updates.inc("refreshGeneration", 1), Updates.set("lastRotatedAt", now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /**
     * Idempotent: revoking an already-revoked (or unknown) session is a safe no-op — repeated
     * logout must never error or leak whether the session was already revoked.
     */
    public void revoke(String sessionId, Instant now) {
        collection().updateOne(
                Filters.and(Filters.eq("_id", sessionId), Filters.eq("revokedAt", null)),
                Updates.set("revokedAt", now));
    }

    /** The authoritative revocation check {@code SessionAuthority} consults. */
    public boolean isActive(CustomerId customerId, SessionId sessionId, Instant now) {
        Document doc = collection().find(Filters.and(Filters.eq("_id", sessionId.value()),
                Filters.eq("customerId", customerId.value()), Filters.eq("revokedAt", null),
                Filters.gt("expiresAt", now))).first();
        return doc != null;
    }
}
