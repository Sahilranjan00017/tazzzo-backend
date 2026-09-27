package com.tazzzo.auth.otp;

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
import java.util.regex.Pattern;

/**
 * PR-11B — orchestrates the OTP challenge lifecycle. This class owns request/verify ONLY: it never
 * creates a customer, a session, or an access token (see {@link OtpVerifiedGrantRepository}).
 *
 * <p>Every failure surfaces as a bounded {@link OtpFailure}; the response given to a caller never
 * reveals whether a phone is already a known customer (this PR does not query any customer
 * collection at all — none exists yet), whether a challenge exists, or how many attempts remain.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final Pattern OTP_SHAPE = Pattern.compile("^[0-9]{6}$");
    private static final int MAX_ATTEMPT_RETRY_ON_SLOT_COLLISION = 3;

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
        Document existing = challenges.findActive(phone, OtpPurpose.LOGIN);
        if (existing != null) {
            Instant resendAvailableAt = existing.getDate("resendAvailableAt").toInstant();
            if (now.isBefore(resendAvailableAt)) {
                // Idempotent within the cooldown: no new send, no new challenge, same identity
                // handed back — a mobile-network retry of the same request must not fan out into
                // repeated SMS sends.
                Instant expiresAt = existing.getDate("expiresAt").toInstant();
                return new OtpRequestResult(existing.getString("_id"),
                        Math.max(0, Duration.between(now, expiresAt).getSeconds()),
                        Math.max(0, Duration.between(now, resendAvailableAt).getSeconds()));
            }
        }
        return createAndDeliver(phone, now);
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

        Document created = insertPendingWithRetryOnSlotCollision(challengeId, phone, verifier, now, expiresAt,
                resendAvailableAt);
        if (created == null) {
            // Another concurrent request now holds the active slot for this phone — hand back
            // ITS identity rather than erroring, so a resend race never yields two usable codes.
            Document active = challenges.findActive(phone, OtpPurpose.LOGIN);
            if (active != null) {
                return new OtpRequestResult(active.getString("_id"),
                        Math.max(0, Duration.between(now, active.getDate("expiresAt").toInstant()).getSeconds()),
                        Math.max(0, Duration.between(now, active.getDate("resendAvailableAt").toInstant()).getSeconds()));
            }
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }

        try {
            deliveryProvider.sendLoginOtp(phone, otp, Duration.ofSeconds(properties.getTtlSeconds()));
        } catch (RuntimeException e) {
            challenges.markDeliveryFailed(challengeId);
            log.warn("otp_delivery_failed provider_error={}", e.getClass().getSimpleName());
            throw new OtpFailure(OtpFailure.Reason.UNAVAILABLE);
        }
        challenges.activateAfterDelivery(challengeId, clock.instant());
        return new OtpRequestResult(challengeId, properties.getTtlSeconds(), properties.getResendCooldownSeconds());
    }

    /**
     * Retries a handful of times ONLY on the create-race duplicate-key exception (the unique
     * partial-index guard rejecting a second concurrent active-slot insert) — never on any other
     * failure. A few retries absorb the tiny window between the supersede step and the insert; if
     * the slot is still contested after that, the caller falls back to reading whichever challenge
     * won.
     */
    private Document insertPendingWithRetryOnSlotCollision(String challengeId, Phone phone, byte[] verifier,
                                                            Instant now, Instant expiresAt,
                                                            Instant resendAvailableAt) {
        for (int attempt = 0; attempt < MAX_ATTEMPT_RETRY_ON_SLOT_COLLISION; attempt++) {
            try {
                return challenges.createPendingAndSupersedePrevious(challengeId, phone, OtpPurpose.LOGIN, verifier,
                        now, expiresAt, resendAvailableAt, properties.getMaxAttempts());
            } catch (MongoWriteException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
            }
        }
        return null;
    }

    private static boolean isDuplicateKey(MongoWriteException e) {
        return e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY;
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
            // LOCKED, SUPERSEDED, VERIFIED (already consumed), EXPIRED, PENDING_DELIVERY,
            // DELIVERY_FAILED — all collapse to the SAME generic outcome (§14/§22: no enumeration).
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }

        Phone phone = new Phone(doc.getString("phoneNormalized"));
        byte[] storedVerifier = java.util.Base64.getDecoder().decode(doc.getString("otpVerifier"));
        if (!codec.matches(storedVerifier, challengeId, phone, OtpPurpose.LOGIN, otp)) {
            challenges.recordWrongAttemptAndLockIfExhausted(challengeId);
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }

        Document verified = challenges.tryMarkVerified(challengeId);
        if (verified == null) {
            // Lost the CAS race (someone else verified/locked/expired it concurrently, or it was
            // already verified) — never issue a second grant for the same challenge.
            throw new OtpFailure(OtpFailure.Reason.INVALID);
        }
        String grantId = OpaqueIds.newGrantId();
        Instant grantExpiresAt = now.plusSeconds(properties.getGrantTtlSeconds());
        grants.insert(grantId, challengeId, phone, OtpPurpose.LOGIN, now, grantExpiresAt);
        return new OtpVerifyResult(challengeId, grantId);
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
