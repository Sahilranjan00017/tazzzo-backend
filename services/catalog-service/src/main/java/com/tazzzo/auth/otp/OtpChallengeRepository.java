package com.tazzzo.auth.otp;

import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
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
 * <p><b>Two independent boolean markers</b>: {@code delivering=true} is present ONLY while a
 * challenge is {@code PENDING_DELIVERY}; {@code active=true} is present ONLY while a challenge is
 * {@code ACTIVE} (i.e. confirmed delivered and currently guessable). Two separate partial-unique
 * indexes on {@code (phoneNormalized, purpose)} — one per marker — enforce "at most one delivery
 * attempt in flight" and "at most one guessable code" independently, so a resend does not invalidate
 * the previously-delivered code until the REPLACEMENT is confirmed delivered.
 *
 * <p><b>Durability hardening pass</b>: the two-write hand-off from confirmed delivery
 * ({@code SUPERSEDED} old + {@code ACTIVE} new) and the two-write success path
 * ({@code VERIFIED} + grant insert) are each wrapped by the CALLER ({@code OtpService}) in a single
 * {@code Tx} (real Mongo multi-document transaction) — this class exposes {@link ClientSession}
 * overloads for exactly those operations so neither hand-off can ever be observed half-applied.
 * {@code expiresAt}/{@code resendAvailableAt} are populated ONLY at {@link #activateAfterDelivery} —
 * the OTP's own validity window starts at CONFIRMED delivery, never at challenge creation, so a slow
 * provider never silently eats into a user's OTP validity. {@code deliveryDeadline} bounds how long
 * a {@code PENDING_DELIVERY} may hold the delivering slot before {@link #retireIfStale} may reclaim
 * it — a process crash between insert and provider-outcome must not permanently block the phone.
 *
 * <p>No direct wall-clock read anywhere in this class — every timestamp is supplied by the caller
 * from the injected {@link java.time.Clock}.
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
     * Inserts a new challenge as {@code PENDING_DELIVERY, delivering=true}. {@code expiresAt}/
     * {@code resendAvailableAt} are NOT set here — they are finalized at
     * {@link #activateAfterDelivery} against the confirmed-delivery instant. Does NOT touch any
     * existing ACTIVE challenge for this phone+purpose.
     *
     * @throws MongoWriteException duplicate key on the {@code delivering=true} partial index — a
     *         concurrent caller is already attempting delivery for this phone+purpose (or a stale
     *         one has not yet been retired via {@link #retireIfStale}).
     */
    public Document createPendingDelivery(String challengeId, Phone phone, OtpPurpose purpose, byte[] verifier,
                                          Instant now, Instant deliveryDeadline, int maxAttempts) {
        Document doc = new Document("_id", challengeId)
                .append("phoneNormalized", phone.value())
                .append("purpose", purpose.name())
                .append("otpVerifier", Base64.getEncoder().encodeToString(verifier))
                .append("createdAt", now)
                .append("deliveryDeadline", deliveryDeadline)
                .append("expiresAt", null)
                .append("resendAvailableAt", null)
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

    /** The current PENDING_DELIVERY challenge holding the delivering slot for a phone+purpose, if any. */
    public Document findDelivering(Phone phone, OtpPurpose purpose) {
        return collection().find(Filters.and(Filters.eq("phoneNormalized", phone.value()),
                Filters.eq("purpose", purpose.name()), Filters.eq("delivering", true))).first();
    }

    /**
     * Durability hardening §7 — atomically retires a PENDING_DELIVERY challenge ONLY if its
     * {@code deliveryDeadline} has passed, freeing the delivering slot for a fresh attempt. A
     * process crash between {@link #createPendingDelivery} and recording the provider outcome must
     * not permanently block a phone until the asynchronous Mongo TTL sweep happens to run.
     *
     * @return true if this call retired the stale challenge; false if it was not stale (or already
     *         resolved by a concurrent caller) — the caller must NOT assume it now owns the slot in
     *         that case.
     */
    public boolean retireIfStale(String challengeId, Instant now) {
        return collection().updateOne(
                Filters.and(Filters.eq("_id", challengeId),
                        Filters.eq("status", OtpChallengeStatus.PENDING_DELIVERY.name()),
                        Filters.lte("deliveryDeadline", now)),
                Updates.combine(Updates.set("status", OtpChallengeStatus.DELIVERY_FAILED.name()),
                        Updates.unset("delivering")))
                .getModifiedCount() > 0;
    }

    /**
     * Durability hardening §2/§3 — confirmed delivery, atomically handled as ONE transaction by the
     * caller ({@code OtpService}) via the supplied {@code session}: retires whatever OLD challenge
     * was ACTIVE for this phone+purpose (if any), THEN activates this one, finalizing
     * {@code expiresAt}/{@code resendAvailableAt} against {@code sentAt} (confirmed-delivery time,
     * not creation time). Because both writes share one transaction, there is no durable state in
     * which the provider confirmed delivery but NEITHER the old nor the new code is usable.
     *
     * @return the activated document, or {@code null} if the expected {@code PENDING_DELIVERY}
     *         transition did not apply (e.g. concurrently retired as stale) — the caller must treat
     *         this as a durability failure, never assume success.
     */
    public Document activateAfterDelivery(ClientSession session, String challengeId, Phone phone, OtpPurpose purpose,
                                          Instant sentAt, Instant expiresAt, Instant resendAvailableAt) {
        supersedeExistingActive(session, phone, purpose);
        return activatePendingToActive(session, challengeId, sentAt, expiresAt, resendAvailableAt);
    }

    /**
     * Step 1 of {@link #activateAfterDelivery}, split out ONLY so a test can exercise the exact
     * failure window it protects against (step 1 genuinely applies, step 2 does not) without
     * duplicating the query — see {@code OtpServiceIT}'s transaction-rollback test. Not meant to be
     * called independently of step 2 in production code.
     */
    void supersedeExistingActive(ClientSession session, Phone phone, OtpPurpose purpose) {
        collection().updateMany(session,
                Filters.and(Filters.eq("phoneNormalized", phone.value()), Filters.eq("purpose", purpose.name()),
                        Filters.eq("active", true)),
                Updates.combine(Updates.set("status", OtpChallengeStatus.SUPERSEDED.name()),
                        Updates.unset("active")));
    }

    /** Step 2 of {@link #activateAfterDelivery} — see step 1's javadoc. */
    Document activatePendingToActive(ClientSession session, String challengeId, Instant sentAt, Instant expiresAt,
                                     Instant resendAvailableAt) {
        return collection().findOneAndUpdate(session,
                Filters.and(Filters.eq("_id", challengeId),
                        Filters.eq("status", OtpChallengeStatus.PENDING_DELIVERY.name())),
                Updates.combine(Updates.set("status", OtpChallengeStatus.ACTIVE.name()),
                        Updates.set("active", true), Updates.unset("delivering"),
                        Updates.set("expiresAt", expiresAt), Updates.set("resendAvailableAt", resendAvailableAt),
                        Updates.set("lastSentAt", sentAt)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /**
     * PENDING_DELIVERY -&gt; DELIVERY_FAILED, freeing the delivery slot for a fresh attempt. The
     * previous ACTIVE challenge (if any) for this phone+purpose is UNTOUCHED and remains fully
     * usable — a failed resend must never destroy a working code.
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
     * Atomic CAS: ACTIVE -&gt; VERIFIED, executed as part of the caller's transaction (see
     * {@code OtpService#verify}) together with the grant insert — durability hardening §1: a real
     * multi-document transaction means either BOTH this transition and the grant exist, or NEITHER
     * does (the challenge reverts to ACTIVE), so a transient grant-insert failure is recoverable by
     * the client simply retrying {@code POST /v1/auth/otp/verify} with the SAME OTP — no
     * out-of-band recovery mechanism is needed. {@code grantId} is still recorded on this same
     * update as the durable record of which grant belongs to this challenge, for any future
     * diagnostic/reconciliation need.
     *
     * <p>The predicate is fully authoritative: {@code status=ACTIVE AND attemptCount<maxAttempts AND
     * expiresAt>now}, all evaluated atomically against the CURRENT document, never against an
     * earlier read. {@code maxAttempts} is passed as the value read earlier in the SAME request —
     * safe because it is immutable for a challenge's lifetime.
     *
     * @return the post-update document (carrying {@code grantId}) on success, or {@code null} if
     *         another request already won the race, the challenge was concurrently
     *         locked/expired/superseded, or the attempt/expiry predicate no longer holds.
     */
    public Document tryMarkVerified(ClientSession session, String challengeId, String grantId, Instant now,
                                    int maxAttemptsAtRead) {
        Bson filter = Filters.and(
                Filters.eq("_id", challengeId),
                Filters.eq("status", OtpChallengeStatus.ACTIVE.name()),
                Filters.lt("attemptCount", maxAttemptsAtRead),
                Filters.gt("expiresAt", now));
        return collection().findOneAndUpdate(session, filter,
                Updates.combine(Updates.set("status", OtpChallengeStatus.VERIFIED.name()),
                        Updates.set("verifiedAt", now), Updates.set("grantId", grantId), Updates.unset("active")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** Non-transactional convenience overload — used by tests exercising the CAS predicate in isolation. */
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
     * courtesy for the read path (never reveal LOCKED distinctly) — the AUTHORITATIVE guard against
     * a stale-ACTIVE correct-OTP win after the limit is reached is {@link #tryMarkVerified}'s own
     * {@code attemptCount<maxAttempts} predicate, which does not depend on this method having
     * already flipped the status to LOCKED.
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
