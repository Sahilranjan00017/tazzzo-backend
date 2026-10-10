package com.tazzzo.auth.otp;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.tx.Tx;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PR-11B — orchestrates the OTP challenge lifecycle. This class owns request/verify ONLY: it never
 * creates a customer, a session, or an access token (see {@link OtpVerifiedGrantRepository}).
 *
 * <p>Every failure surfaces as a bounded {@link OtpFailure}; the response given to a caller never
 * reveals whether a phone is already a known customer (this PR does not query any customer
 * collection at all — none exists yet), whether a challenge exists, or how many attempts remain.
 *
 * <p><b>Durability hardening pass</b> — the two multi-write hand-offs in this lifecycle are each a
 * single real Mongo transaction (via {@link Tx}, the SAME primitive {@code PricingService}'s T1-T7
 * write path already uses), so neither can ever be observed half-applied:
 * <ul>
 *   <li>Confirmed delivery: retiring the old ACTIVE challenge and activating the new one
 *       ({@link OtpChallengeRepository#activateAfterDelivery}).</li>
 *   <li>Successful verification: the ACTIVE-&gt;VERIFIED transition and the grant insert
 *       ({@link OtpChallengeRepository#tryMarkVerified} + {@link OtpVerifiedGrantRepository#insert}).
 *       A transient failure aborts the WHOLE transaction — the challenge reverts to ACTIVE, so the
 *       client's own retry of {@code POST /v1/auth/otp/verify} with the same OTP is the recovery
 *       path, with no out-of-band repair ever required.</li>
 * </ul>
 *
 * <p>No direct wall-clock read anywhere in this class or the OTP repositories — every timestamp used
 * for a business decision comes from the injected {@link Clock}, read ONCE per request/verify call.
 */
@Service
public class OtpService {

    /** Immutable result of the verify transaction callback (never a mutable holder). */
    private enum VerifyTxOutcome { VERIFIED, NOT_VERIFIED }

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final Pattern OTP_SHAPE = Pattern.compile("^[0-9]{6}$");

    private final OtpChallengeRepository challenges;
    private final OtpVerifiedGrantRepository grants;
    private final OtpVerifierCodec codec;
    private final OtpAuthProperties properties;
    private final Clock clock;
    private final Tx tx;
    private final ObjectProvider<OtpRateLimiter> rateLimiter;
    private final ObjectProvider<OtpDeliveryProvider> provider;

    public OtpService(OtpChallengeRepository challenges, OtpVerifiedGrantRepository grants,
                      OtpVerifierCodec codec, OtpAuthProperties properties, Clock clock, Tx tx,
                      ObjectProvider<OtpRateLimiter> rateLimiter, ObjectProvider<OtpDeliveryProvider> provider) {
        this.challenges = challenges;
        this.grants = grants;
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        this.rateLimiter = rateLimiter;
        this.provider = provider;
    }

    public OtpRequestResult request(String rawPhone, String clientIp) {
        Phone phone = parsePhone(rawPhone);
        codec.requireReady();
        String phoneDigest = codec.phoneBucketDigest(phone);
        admitRequest(clientIp, phoneDigest);

        Instant now = clock.instant();
        // findActive means ACTIVE ONLY (confirmed delivered) — a PENDING_DELIVERY challenge from a
        // concurrent in-flight request is NEVER treated as returnable here: this caller must not
        // observe, and hand back as if successful, a delivery that has not yet been confirmed.
        Document activeExisting = challenges.findActive(phone, OtpPurpose.LOGIN);
        if (activeExisting != null) {
            Instant resendAvailableAt = activeExisting.getDate("resendAvailableAt").toInstant();
            if (now.isBefore(resendAvailableAt)) {
                // Idempotent within the cooldown: no new send, no new challenge, same identity
                // handed back — a mobile-network retry of the same request must not fan out into
                // repeated SMS sends.
                return resultFrom(activeExisting, now);
            }
            // Cooldown elapsed: fall through to a resend. The OLD challenge stays ACTIVE and fully
            // usable until the REPLACEMENT is confirmed delivered — createAndDeliver does not touch
            // it up front.
        }
        return createAndDeliver(phone, now);
    }

    private static OtpRequestResult resultFrom(Document doc, Instant now) {
        Instant expiresAt = doc.getDate("expiresAt").toInstant();
        Instant resendAvailableAt = doc.getDate("resendAvailableAt").toInstant();
        return new OtpRequestResult(doc.getString("_id"),
                Math.max(0, Duration.between(now, expiresAt).getSeconds()),
                Math.max(0, Duration.between(now, resendAvailableAt).getSeconds()));
    }

    private OtpRequestResult createAndDeliver(Phone phone, Instant now) {
        OtpDeliveryProvider deliveryProvider = provider.getIfAvailable();
        if (deliveryProvider == null) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
        // Durability §6: the OTP's own validity window is finalized at CONFIRMED delivery
        // (activateAfterDelivery), never here — a slow provider must not silently eat into it.
        // deliveryDeadline is a SEPARATE, short bound on how long the delivering slot itself may be
        // held, independent of OTP validity.
        Instant deliveryDeadline = now.plusSeconds(properties.getDeliveryTimeoutSeconds());
        String challengeId = OpaqueIds.newChallengeId();
        String otp = OtpCodeGenerator.generate();
        byte[] verifier = codec.verifierFor(challengeId, phone, OtpPurpose.LOGIN, otp);

        insertPendingDeliveryRecoveringStaleSlot(challengeId, phone, verifier, now, deliveryDeadline);

        try {
            deliveryProvider.sendLoginOtp(phone, otp, Duration.ofSeconds(properties.getTtlSeconds()));
        } catch (RuntimeException e) {
            // The previous ACTIVE challenge (if any) is UNTOUCHED — only THIS pending one is
            // retired, so a failed resend never destroys a working code.
            challenges.markDeliveryFailed(challengeId);
            log.warn("otp_delivery_failed provider_error={}", e.getClass().getSimpleName());
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }

        Instant sentAt = clock.instant();
        Instant expiresAt = sentAt.plusSeconds(properties.getTtlSeconds());
        Instant resendAvailableAt = sentAt.plusSeconds(properties.getResendCooldownSeconds());
        OtpRequestResult activatedResult;
        try {
            activatedResult = tx.call(session -> {
                // activateAfterDelivery performs TWO writes in this transaction: (1) supersede the
                // old ACTIVE challenge, (2) activate this PENDING_DELIVERY one. If (2) does not
                // apply, (1) MUST be rolled back too — checking the result only AFTER the transaction
                // returns would be too late: the transaction would already have committed (1) alone,
                // recreating the exact durable zero-usable-code state this transaction exists to
                // prevent. Throwing HERE, inside the callback, is what forces the abort.
                Document activated = challenges.activateAfterDelivery(session, challengeId, phone,
                        OtpPurpose.LOGIN, sentAt, expiresAt, resendAvailableAt);
                if (activated == null) {
                    throw new OtpTransactionAbortedException("otp activation transition did not apply");
                }
                // Pure, immutable value derived only from this attempt's own writes.
                return new OtpRequestResult(challengeId,
                        secondsUntil(sentAt, activated.getDate("expiresAt").toInstant()),
                        secondsUntil(sentAt, activated.getDate("resendAvailableAt").toInstant()));
            });
        } catch (OtpTransactionAbortedException e) {
            // Durability §2/§3: the confirmed-delivered code must never be silently unusable. This
            // "should never happen" in the normal flow (nothing else can touch a delivering-owned
            // PENDING_DELIVERY except this same call), so treat it as a hard failure rather than
            // fabricating a 202 the server cannot actually back. Because the transaction aborted,
            // the OLD challenge (if any) was NEVER superseded — it remains exactly as it was.
            log.error("otp_activation_transition_missing challenge_present=true");
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        } catch (RuntimeException e) {
            log.error("otp_activation_transaction_failed", e);
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
        return activatedResult;
    }

    /**
     * Whole seconds from {@code from} to the stored instant, rounded UP and clamped to >= 0. Mongo truncates dates to
     * milliseconds, so the stored instant can sit up to 1 ms BEFORE {@code from + configured}; flooring that gave 299 for a
     * 300 s TTL, 1 for a 2 s cooldown and -1 for a 0 s cooldown. Ceiling reports the configured value exactly at issue time.
     */
    static long secondsUntil(Instant from, Instant to) {
        return Math.max(0L, Math.floorDiv(Duration.between(from, to).toMillis() + 999L, 1000L));
    }

    /**
     * Durability §7 — a duplicate-key collision on the "delivering" slot means someone else holds
     * it. Before refusing outright, checks whether that holder is STALE (its {@code
     * deliveryDeadline} has passed — most likely a crashed process that never recorded a provider
     * outcome) and, if so, retires it and retries ONCE. Bounded: no loop, no busy-wait, no sleep — a
     * genuinely in-flight (non-stale) holder still results in an immediate refusal.
     */
    private void insertPendingDeliveryRecoveringStaleSlot(String challengeId, Phone phone, byte[] verifier,
                                                          Instant now, Instant deliveryDeadline) {
        try {
            challenges.createPendingDelivery(challengeId, phone, OtpPurpose.LOGIN, verifier, now, deliveryDeadline,
                    properties.getMaxAttempts());
            return;
        } catch (MongoWriteException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
        }
        Document holder = challenges.findDelivering(phone, OtpPurpose.LOGIN);
        boolean staleAndRetired = holder != null && !now.isBefore(holder.getDate("deliveryDeadline").toInstant())
                && challenges.retireIfStale(holder.getString("_id"), now);
        if (!staleAndRetired) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
        try {
            challenges.createPendingDelivery(challengeId, phone, OtpPurpose.LOGIN, verifier, now, deliveryDeadline,
                    properties.getMaxAttempts());
        } catch (MongoWriteException e2) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
    }

    private static boolean isDuplicateKey(MongoWriteException e) {
        return e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }

    public OtpVerifyResult verify(String challengeId, String otp, String clientIp) {
        if (challengeId == null || !OpaqueIds.CHALLENGE_ID.matcher(challengeId).matches()
                || otp == null || !OTP_SHAPE.matcher(otp).matches()) {
            throw new OtpFailure(OtpFailure.Reason.INVALID_REQUEST);
        }
        codec.requireReady();
        admitVerify(clientIp, challengeId);

        Instant now = clock.instant();
        Document doc = challenges.findById(challengeId);
        if (doc == null) {
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }
        OtpChallengeStatus status = OtpChallengeStatus.valueOf(doc.getString("status"));
        if (status == OtpChallengeStatus.ACTIVE && !doc.getDate("expiresAt").toInstant().isAfter(now)) {
            challenges.markExpiredIfPastDeadline(challengeId, now);
            throw new OtpFailure(OtpFailure.Reason.EXPIRED);
        }
        if (status != OtpChallengeStatus.ACTIVE) {
            // LOCKED, SUPERSEDED, VERIFIED (already verified), EXPIRED, PENDING_DELIVERY,
            // DELIVERY_FAILED — all collapse to the SAME generic outcome (no enumeration).
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }

        Phone phone = new Phone(doc.getString("phoneNormalized"));
        int maxAttemptsAtRead = doc.getInteger("maxAttempts", Integer.MAX_VALUE);
        byte[] storedVerifier = Base64.getDecoder().decode(doc.getString("otpVerifier"));
        if (!codec.matches(storedVerifier, challengeId, phone, OtpPurpose.LOGIN, otp)) {
            challenges.recordWrongAttemptAndLockIfExhausted(challengeId);
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }

        // Durability §1: the CAS (status=ACTIVE AND attemptCount<maxAttempts AND expiresAt>now) and
        // the grant insert are ONE transaction. Either both commit, or neither does — a transient
        // grant-insert failure simply aborts, leaving the challenge ACTIVE, so the caller's own
        // retry of this exact HTTP call with the same OTP is the recovery path.
        String grantId = OpaqueIds.newGrantId();
        Instant grantExpiresAt = now.plusSeconds(properties.getGrantTtlSeconds());
        // Tx.call: the value returned is the LAST callback attempt's result, straight from the
        // driver's own retry loop — an attempt whose commit was rolled back can never leak into it
        // (PR-11D; the former external holder could). grantId/grantExpiresAt/maxAttemptsAtRead are
        // fixed BEFORE the transaction, so every attempt of this request uses the same grant id.
        VerifyTxOutcome outcome;
        try {
            outcome = tx.call(session -> {
                Document verified = challenges.tryMarkVerified(session, challengeId, grantId, now, maxAttemptsAtRead);
                if (verified == null) {
                    return VerifyTxOutcome.NOT_VERIFIED;
                }
                insertGrantOrReconcile(session, grantId, challengeId, phone, OtpPurpose.LOGIN, now, grantExpiresAt);
                return VerifyTxOutcome.VERIFIED;
            });
        } catch (OtpFailure e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("otp_verification_transaction_failed", e);
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }

        if (outcome == VerifyTxOutcome.NOT_VERIFIED) {
            // Re-read to give EXPIRED its distinct (already-public) outcome when that is genuinely
            // why the CAS lost; every other reason (locked/superseded/already-verified/attempts
            // exhausted concurrently) collapses to the same generic INVALID.
            Document reread = challenges.findById(challengeId);
            if (reread != null && OtpChallengeStatus.valueOf(reread.getString("status")) == OtpChallengeStatus.ACTIVE
                    && !reread.getDate("expiresAt").toInstant().isAfter(now)) {
                challenges.markExpiredIfPastDeadline(challengeId, now);
                throw new OtpFailure(OtpFailure.Reason.EXPIRED);
            }
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }
        return new OtpVerifyResult(challengeId, grantId);
    }

    /**
     * Durability §4 — checks for an existing grant FIRST (a snapshot read inside the SAME
     * transaction) rather than reacting to a duplicate-key exception: this is the common path for
     * the (extremely rare, corruption-only) case this method exists to guard, and avoids depending
     * on how the driver/server label a unique-index conflict raised from inside a transaction. A
     * genuine concurrent race that slips past this read is still caught by the unique index at
     * insert time — that raises a write conflict, which is a transient-transaction condition
     * {@code Tx}'s {@code withTransaction} retries automatically, and the retry's own read-first
     * check then reconciles correctly.
     *
     * <p>An existing grant is accepted ONLY if it is an EXACT match on every immutable identity
     * field; a mismatch means corruption/a bug, and must fail loudly rather than silently returning
     * a grantId that does not actually correspond to what was just verified.
     */
    private void insertGrantOrReconcile(ClientSession session, String grantId, String challengeId, Phone phone,
                                        OtpPurpose purpose, Instant now, Instant expiresAt) {
        Document existing = grants.findByChallengeId(session, challengeId);
        if (existing != null) {
            boolean identityMatches = Objects.equals(grantId, existing.getString("_id"))
                    && Objects.equals(challengeId, existing.getString("challengeId"))
                    && Objects.equals(purpose.name(), existing.getString("purpose"))
                    && Objects.equals(phone.value(), existing.getString("phoneNormalized"));
            if (!identityMatches) {
                log.error("otp_grant_integrity_violation challenge_id_present=true");
                throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
            }
            return; // exact match already exists — idempotent no-op, not an error.
        }
        grants.insert(session, grantId, challengeId, phone, purpose, now, expiresAt);
    }

    private static Phone parsePhone(String raw) {
        try {
            return Phone.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new OtpFailure(OtpFailure.Reason.INVALID_REQUEST);
        }
    }

    private void admitRequest(String clientIp, String phoneDigest) {
        OtpRateLimiter limiter = rateLimiter.getIfAvailable();
        if (limiter == null) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
        applyAdmission(limiter.admitRequest(clientIp, phoneDigest));
    }

    private void admitVerify(String clientIp, String challengeId) {
        OtpRateLimiter limiter = rateLimiter.getIfAvailable();
        if (limiter == null) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
        applyAdmission(limiter.admitVerify(clientIp, challengeId));
    }

    private static void applyAdmission(Admission admission) {
        if (admission instanceof Admission.RateLimited limited) {
            throw new OtpFailure(OtpFailure.Reason.RATE_LIMITED, limited.retryAfter());
        }
        if (admission instanceof Admission.Unavailable) {
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
    }
}
