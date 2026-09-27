package com.tazzzo.auth.otp;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.tazzzo.catalog.ratelimit.Admission;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PR-11B — orchestrates the OTP challenge lifecycle. This class owns request/verify ONLY: it never
 * creates a customer, a session, or an access token (see {@link OtpVerifiedGrantRepository}).
 *
 * <p>Every failure surfaces as a bounded {@link OtpFailure}; the response given to a caller never
 * reveals whether a phone is already a known customer (this PR does not query any customer
 * collection at all — none exists yet), whether a challenge exists, or how many attempts remain.
 *
 * <p><b>No direct wall-clock read anywhere in this class or the OTP repositories</b> — every
 * timestamp used for a business decision comes from the injected {@link Clock}, read ONCE per
 * request/verify call so every check in that call is decided against the SAME instant.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final Pattern OTP_SHAPE = Pattern.compile("^[0-9]{6}$");
    /** Bounded, immediate (no delay) retries recovering a transient grant-insert failure. */
    private static final int MAX_GRANT_INSERT_ATTEMPTS = 3;

    private final OtpChallengeRepository challenges;
    private final OtpVerifiedGrantRepository grants;
    private final OtpVerifierCodec codec;
    private final OtpAuthProperties properties;
    private final Clock clock;
    private final ObjectProvider<OtpRateLimiter> rateLimiter;
    private final ObjectProvider<OtpDeliveryProvider> provider;

    public OtpService(OtpChallengeRepository challenges, OtpVerifiedGrantRepository grants,
                      OtpVerifierCodec codec, OtpAuthProperties properties, Clock clock,
                      ObjectProvider<OtpRateLimiter> rateLimiter, ObjectProvider<OtpDeliveryProvider> provider) {
        this.challenges = challenges;
        this.grants = grants;
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
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
        // concurrent in-flight request is NEVER treated as returnable here (hardening §2): this
        // caller must not observe, and hand back as if successful, a delivery that has not yet been
        // confirmed.
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
            // usable until the REPLACEMENT is confirmed delivered (hardening §8) — createAndDeliver
            // does not touch it up front.
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
        Instant expiresAt = now.plusSeconds(properties.getTtlSeconds());
        Instant resendAvailableAt = now.plusSeconds(properties.getResendCooldownSeconds());
        String challengeId = OpaqueIds.newChallengeId();
        String otp = OtpCodeGenerator.generate();
        byte[] verifier = codec.verifierFor(challengeId, phone, OtpPurpose.LOGIN, otp);

        try {
            challenges.createPendingDelivery(challengeId, phone, OtpPurpose.LOGIN, verifier, now, expiresAt,
                    resendAvailableAt, properties.getMaxAttempts());
        } catch (MongoWriteException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            // Someone else is ALREADY attempting delivery for this phone right now (the
            // "delivering" partial-unique index rejected a second concurrent insert). This caller
            // must not claim success for a delivery it did not perform and cannot observe the
            // outcome of — bounded immediate refusal, no busy-wait, no sleep, no retry (hardening
            // §2): the in-flight request will resolve on its own within its own timeout.
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }

        try {
            deliveryProvider.sendLoginOtp(phone, otp, Duration.ofSeconds(properties.getTtlSeconds()));
        } catch (RuntimeException e) {
            // The previous ACTIVE challenge (if any) is UNTOUCHED — only THIS pending one is
            // retired, so a failed resend never destroys a working code (hardening §8).
            challenges.markDeliveryFailed(challengeId);
            log.warn("otp_delivery_failed provider_error={}", e.getClass().getSimpleName());
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
        // Confirmed delivered: NOW (and only now) the old code, if any, is retired and this one
        // becomes the single usable code (hardening §8).
        challenges.activateAfterDelivery(challengeId, phone, OtpPurpose.LOGIN, clock.instant());
        return new OtpRequestResult(challengeId, properties.getTtlSeconds(), properties.getResendCooldownSeconds());
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
            // DELIVERY_FAILED — all collapse to the SAME generic outcome (§12/§22: no enumeration).
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }

        Phone phone = new Phone(doc.getString("phoneNormalized"));
        int maxAttemptsAtRead = doc.getInteger("maxAttempts", Integer.MAX_VALUE);
        byte[] storedVerifier = Base64.getDecoder().decode(doc.getString("otpVerifier"));
        if (!codec.matches(storedVerifier, challengeId, phone, OtpPurpose.LOGIN, otp)) {
            challenges.recordWrongAttemptAndLockIfExhausted(challengeId);
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }

        // The CAS predicate — status=ACTIVE AND attemptCount<maxAttempts AND expiresAt>now — is
        // fully authoritative and evaluated against the CURRENT document, never the earlier read
        // above (hardening §3/§4): a final exhausting wrong attempt, or an expiry, landing between
        // the read and here cannot be raced past by a concurrent correct guess.
        String grantId = OpaqueIds.newGrantId();
        Document verified = challenges.tryMarkVerified(challengeId, grantId, now, maxAttemptsAtRead);
        if (verified == null) {
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

        // grantId is now DURABLY recorded on the challenge document by the SAME atomic update that
        // just ran — recover using it, never mint a second one, even across a transient failure
        // inserting the grant document itself (hardening §1).
        String recordedGrantId = verified.getString("grantId");
        Instant grantExpiresAt = now.plusSeconds(properties.getGrantTtlSeconds());
        ensureGrantExists(recordedGrantId, challengeId, phone, OtpPurpose.LOGIN, now, grantExpiresAt);
        return new OtpVerifyResult(challengeId, recordedGrantId);
    }

    /**
     * Idempotently ensures exactly one grant document exists for {@code grantId}. A duplicate-key
     * exception means the grant already exists (a previous attempt of THIS SAME verification
     * already created it) — that is SUCCESS, not an error, and no second grant is ever created. Any
     * other exception is retried a bounded number of times with NO delay (hardening §1); only after
     * exhausting the budget does this surface as {@link OtpFailure.Reason#UNAVAILABLE} — the
     * challenge remains durably VERIFIED with {@code grantId} recorded, so a later retry of this
     * exact recovery (not a public re-verification endpoint) could still complete it.
     */
    private void ensureGrantExists(String grantId, String challengeId, Phone phone, OtpPurpose purpose,
                                   Instant now, Instant expiresAt) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_GRANT_INSERT_ATTEMPTS; attempt++) {
            try {
                grants.insert(grantId, challengeId, phone, purpose, now, expiresAt);
                return;
            } catch (MongoWriteException e) {
                if (isDuplicateKey(e)) {
                    return; // already exists — a prior attempt of this same recovery succeeded.
                }
                lastFailure = e;
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }
        log.error("otp_grant_persist_failed challenge_id_present=true attempts={}", MAX_GRANT_INSERT_ATTEMPTS,
                lastFailure);
        throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
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
