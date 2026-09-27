package com.tazzzo.auth.otp;

import com.mongodb.MongoWriteException;
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
import java.util.Base64;

/**
 * PR-11B — raw-driver Mongo access for {@code customer_otp_challenges}, following this repository's
 * established convention (no MongoTemplate/Spring Data repository — see {@code PricingService}):
 * every state transition is an atomic {@code updateOne}/{@code findOneAndUpdate} filtered on the
 * EXPECTED current state, never a read-then-write-back.
 *
 * <p><b>Two independent boolean markers, not one</b> (hardening pass): {@code delivering=true} is
 * present ONLY while a challenge is {@code PENDING_DELIVERY}; {@code active=true} is present ONLY
 * while a challenge is {@code ACTIVE} (i.e. confirmed delivered and currently guessable). Two
 * separate partial-unique indexes on {@code (phoneNormalized, purpose)} — one per marker — enforce
 * "at most one delivery attempt in flight" and "at most one guessable code" independently. This is
 * deliberate: a resend must NOT invalidate the previously-delivered, still-working code until the
 * REPLACEMENT is confirmed delivered (see {@code OtpService#createAndDeliver}) — collapsing both
 * concerns into one marker (the pre-hardening design) meant a failed resend destroyed a perfectly
 * good code. No direct wall-clock read anywhere in this class — every timestamp is supplied by the
 * caller from the injected {@link java.time.Clock}.
 *
 * <p><b>Time and attempt limits are enforced IN the CAS predicate</b>, never only by an earlier read
 * — {@link #tryMarkVerified} requires {@code status=ACTIVE AND attemptCount<maxAttempts AND
 * expiresAt>now} atomically, closing the TOCTOU window a read-then-decide-then-write pattern would
 * leave open.
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
     * Inserts a new challenge as {@code PENDING_DELIVERY, delivering=true}. Does NOT touch any
     * existing ACTIVE challenge for this phone+purpose — the previous code stays fully usable until
     * THIS challenge is confirmed delivered (see {@link #activateAfterDelivery}).
     *
     * @throws MongoWriteException duplicate key on the {@code delivering=true} partial index — a
     *         concurrent caller is already attempting delivery for this phone+purpose. The caller
     *         must NOT claim success in that case (PR-11B hardening §2).
     */
    public Document createPendingDelivery(String challengeId, Phone phone, OtpPurpose purpose, byte[] verifier,
                                          Instant now, Instant expiresAt, Instant resendAvailableAt,
                                          int maxAttempts) {
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
                .append("delivering", true)
                .append("verifiedAt", null)
                .append("grantId", null)
                .append("lastSentAt", null);
        collection().insertOne(doc);
        return doc;
    }

    /**
     * Confirmed delivery: atomically retires whatever OLD challenge was ACTIVE for this
     * phone+purpose (if any), THEN activates this one. The two updates are sequential, not one
     * cross-document transaction — deliberately ordered so there is never a window with TWO active
     * challenges (the old one is cleared first), only a vanishingly brief window with ZERO, which a
     * concurrent verify against the old challenge would see as "no longer active" (safe: it is
     * genuinely being replaced) rather than ever accepting two different codes at once.
     */
    public void activateAfterDelivery(String challengeId, Phone phone, OtpPurpose purpose, Instant sentAt) {
        collection().updateMany(
                Filters.and(Filters.eq("phoneNormalized", phone.value()), Filters.eq("purpose", purpose.name()),
                        Filters.eq("active", true)),
                Updates.combine(Updates.set("status", OtpChallengeStatus.SUPERSEDED.name()),
                        Updates.unset("active")));
        collection().updateOne(
                Filters.and(Filters.eq("_id", challengeId),
                        Filters.eq("status", OtpChallengeStatus.PENDING_DELIVERY.name())),
                Updates.combine(Updates.set("status", OtpChallengeStatus.ACTIVE.name()),
                        Updates.set("active", true), Updates.unset("delivering"), Updates.set("lastSentAt", sentAt)));
    }

    /**
     * PENDING_DELIVERY -&gt; DELIVERY_FAILED, freeing the delivery slot for a fresh attempt. The
     * previous ACTIVE challenge (if any) for this phone+purpose is UNTOUCHED and remains fully
     * usable — a failed resend must never destroy a working code (PR-11B hardening §8).
     */
    public void markDeliveryFailed(String challengeId) {
        collection().updateOne(
                Filters.and(Filters.eq("_id", challengeId),
                        Filters.eq("status", OtpChallengeStatus.PENDING_DELIVERY.name())),
                Updates.combine(Updates.set("status", OtpChallengeStatus.DELIVERY_FAILED.name()),
                        Updates.unset("delivering")));
    }

    public Document findById(String challengeId) {
        return collection().find(Filters.eq("_id", challengeId)).first();
    }

    /** The current ACTIVE (confirmed-delivered, guessable) challenge for a phone+purpose, if any. */
    public Document findActive(Phone phone, OtpPurpose purpose) {
        return collection().find(Filters.and(Filters.eq("phoneNormalized", phone.value()),
                Filters.eq("purpose", purpose.name()), Filters.eq("active", true))).first();
    }

    /**
     * Atomic CAS: ACTIVE -&gt; VERIFIED, and records {@code grantId} on the SAME atomic update — the
     * challenge document becomes the durable, idempotent record of which grant belongs to it, so a
     * transient failure inserting the grant document afterward can always be recovered by re-reading
     * this SAME {@code grantId} rather than minting a second one (PR-11B hardening §1).
     *
     * <p>The predicate is fully authoritative: {@code status=ACTIVE AND attemptCount<maxAttempts AND
     * expiresAt>now}, all evaluated atomically against the CURRENT document, never against an
     * earlier read (hardening §3/§4). {@code maxAttempts} is passed as the value read earlier in the
     * SAME request — safe because it is immutable for a challenge's lifetime, so it cannot go stale
     * between the read and this CAS.
     *
     * @return the post-update document (carrying {@code grantId}) on success, or {@code null} if
     *         another request already won the race, the challenge was concurrently
     *         locked/expired/superseded, or the attempt/expiry predicate no longer holds — the
     *         caller must NOT create a second usable grant in that case.
     */
    public Document tryMarkVerified(String challengeId, String grantId, Instant now, int maxAttemptsAtRead) {
        Bson filter = Filters.and(
                Filters.eq("_id", challengeId),
                Filters.eq("status", OtpChallengeStatus.ACTIVE.name()),
                Filters.lt("attemptCount", maxAttemptsAtRead),
                Filters.gt("expiresAt", now));
        return collection().findOneAndUpdate(filter,
                Updates.combine(Updates.set("status", OtpChallengeStatus.VERIFIED.name()),
                        Updates.set("verifiedAt", now), Updates.set("grantId", grantId), Updates.unset("active")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /**
     * Atomically increments the attempt counter, then — in a SEPARATE atomic step, safe to run
     * concurrently — locks the challenge once the counter has reached the configured maximum.
     * Two simultaneous wrong-attempt requests each increment independently ({@code $inc} is
     * inherently race-safe), so neither can under-count the other's attempt. Locking here is a
     * courtesy for the read path (§12: never reveal LOCKED distinctly) — the AUTHORITATIVE guard
     * against a stale-ACTIVE correct-OTP win after the limit is reached is
     * {@link #tryMarkVerified}'s own {@code attemptCount<maxAttempts} predicate, which does not
     * depend on this method having already flipped the status to LOCKED.
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
