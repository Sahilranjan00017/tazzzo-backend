package com.tazzzo.auth.otp;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;

/**
 * PR-11B — raw-driver Mongo access for {@code customer_otp_challenges}, following this repository's
 * established convention (no MongoTemplate/Spring Data repository — see {@code PricingService}):
 * every state transition is an atomic {@code updateOne}/{@code findOneAndUpdate} filtered on the
 * EXPECTED current state, never a read-then-write-back. {@code active=true} is present ONLY while a
 * challenge is {@code PENDING_DELIVERY} or {@code ACTIVE}; the unique index on
 * {@code (phoneNormalized, purpose)} filtered by {@code active=true} is the Mongo-enforced
 * create-race guard for "at most one usable challenge per phone+purpose" — the same idiom
 * {@code SchemaBootstrap} already uses for {@code service_areas.pincode} and
 * {@code catalogue_releases.gate}.
 */
@Component
public class OtpChallengeRepository {

    public static final String COLLECTION = "customer_otp_challenges";

    private final MongoDatabase db;

    public OtpChallengeRepository(MongoDatabase db) {
        this.db = db;
    }

    private MongoCollection<Document> collection() {
        return db.getCollection(COLLECTION);
    }

    /**
     * Atomically retires any currently-active challenge for this phone+purpose, THEN inserts the
     * new one as {@code PENDING_DELIVERY}. The unique partial index protects against two concurrent
     * callers both landing an active slot even though these two steps are not one atomic operation —
     * whichever insert loses the race gets a {@link MongoWriteException} (duplicate key), which the
     * caller treats as "someone else just created the active challenge" and re-reads it instead of
     * retrying blindly.
     *
     * @throws MongoWriteException duplicate key — a concurrent caller already holds the active slot
     */
    public Document createPendingAndSupersedePrevious(String challengeId, Phone phone, OtpPurpose purpose,
                                                       byte[] verifier, Instant now, Instant expiresAt,
                                                       Instant resendAvailableAt, int maxAttempts) {
        collection().updateMany(
                Filters.and(Filters.eq("phoneNormalized", phone.value()), Filters.eq("purpose", purpose.name()),
                        Filters.eq("active", true)),
                Updates.combine(Updates.set("status", OtpChallengeStatus.SUPERSEDED.name()),
                        Updates.unset("active")));
        Document doc = new Document("_id", challengeId)
                .append("phoneNormalized", phone.value())
                .append("purpose", purpose.name())
                .append("otpVerifier", Base64.getEncoder().encodeToString(verifier))
                .append("createdAt", now)
                .append("expiresAt", expiresAt)
                .append("resendAvailableAt", resendAvailableAt)
                .append("attemptCount", 0)
                .append("maxAttempts", maxAttempts)
                .append("status", OtpChallengeStatus.PENDING_DELIVERY.name())
                .append("active", true)
                .append("consumedAt", null)
                .append("lastSentAt", null);
        collection().insertOne(doc);
        return doc;
    }

    /** PENDING_DELIVERY -> ACTIVE after a confirmed provider send. Stays in the active slot. */
    public void activateAfterDelivery(String challengeId, Instant sentAt) {
        collection().updateOne(
                Filters.and(Filters.eq("_id", challengeId),
                        Filters.eq("status", OtpChallengeStatus.PENDING_DELIVERY.name())),
                Updates.combine(Updates.set("status", OtpChallengeStatus.ACTIVE.name()),
                        Updates.set("lastSentAt", sentAt)));
    }

    /** PENDING_DELIVERY -> DELIVERY_FAILED, freeing the phone+purpose slot for a fresh attempt. */
    public void markDeliveryFailed(String challengeId) {
        collection().updateOne(
                Filters.and(Filters.eq("_id", challengeId),
                        Filters.eq("status", OtpChallengeStatus.PENDING_DELIVERY.name())),
                Updates.combine(Updates.set("status", OtpChallengeStatus.DELIVERY_FAILED.name()),
                        Updates.unset("active")));
    }

    public Document findById(String challengeId) {
        return collection().find(Filters.eq("_id", challengeId)).first();
    }

    /** The current active (PENDING_DELIVERY or ACTIVE) challenge for a phone+purpose, if any. */
    public Document findActive(Phone phone, OtpPurpose purpose) {
        return collection().find(Filters.and(Filters.eq("phoneNormalized", phone.value()),
                Filters.eq("purpose", purpose.name()), Filters.eq("active", true))).first();
    }

    /**
     * Atomic CAS: ACTIVE -&gt; VERIFIED, filtered on the expected current status. Returns the
     * post-update document on success, or {@code null} if another request already won the race (or
     * the challenge was concurrently locked/expired/superseded) — the caller must NOT create a
     * second usable grant in that case.
     */
    public Document tryMarkVerified(String challengeId) {
        return collection().findOneAndUpdate(
                Filters.and(Filters.eq("_id", challengeId), Filters.eq("status", OtpChallengeStatus.ACTIVE.name())),
                Updates.combine(Updates.set("status", OtpChallengeStatus.VERIFIED.name()),
                        Updates.set("consumedAt", Instant.now()), Updates.unset("active")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /**
     * Atomically increments the attempt counter, then — in a SEPARATE atomic step, safe to run
     * concurrently — locks the challenge once the counter has reached the configured maximum.
     * Two simultaneous wrong-attempt requests each increment independently ({@code $inc} is
     * inherently race-safe), so neither can under-count the other's attempt.
     */
    public void recordWrongAttemptAndLockIfExhausted(String challengeId) {
        Document updated = collection().findOneAndUpdate(
                Filters.and(Filters.eq("_id", challengeId), Filters.eq("status", OtpChallengeStatus.ACTIVE.name())),
                Updates.inc("attemptCount", 1),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) {
            return;
        }
        int attempts = updated.getInteger("attemptCount", 0);
        int max = updated.getInteger("maxAttempts", Integer.MAX_VALUE);
        if (attempts >= max) {
            collection().updateOne(
                    Filters.and(Filters.eq("_id", challengeId),
                            Filters.eq("status", OtpChallengeStatus.ACTIVE.name())),
                    Updates.combine(Updates.set("status", OtpChallengeStatus.LOCKED.name()), Updates.unset("active")));
        }
    }

    /** Application-enforced expiry — never relies solely on the asynchronous Mongo TTL sweep. */
    public void markExpiredIfPastDeadline(String challengeId, Instant now) {
        collection().updateOne(
                Filters.and(Filters.eq("_id", challengeId), Filters.eq("status", OtpChallengeStatus.ACTIVE.name()),
                        Filters.lte("expiresAt", now)),
                Updates.combine(Updates.set("status", OtpChallengeStatus.EXPIRED.name()), Updates.unset("active")));
    }
}
