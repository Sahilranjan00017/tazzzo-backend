package com.tazzzo.auth.otp;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import com.tazzzo.catalog.tx.RetryInjectingTx;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-11D — OTP transaction retry safety. A REAL Mongo transaction whose commit "fails transiently"
 * (see {@link RetryInjectingTx}) so the driver aborts the attempt and re-invokes the callback. The
 * outcome must always be that of the attempt that actually committed — never a value left behind by
 * an attempt that was rolled back.
 */
@SpringBootTest(classes = {CatalogApplication.class, OtpTransactionRetryIT.TestBeans.class})
class OtpTransactionRetryIT extends AbstractMongoIT {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    // Deliberately in the FUTURE: documents written with this clock land in TTL-indexed collections
    // (expireAfter 0 on expiresAt). A base in the real past makes the Mongo TTL monitor delete them
    // mid-test (flaky EXPIRED->INVALID). Must stay later than any real wall-clock time CI can reach.
    static final AtomicReference<Instant> CLOCK_NOW = new AtomicReference<>(Instant.parse("2099-06-01T00:00:00Z"));
    static final String IP = "203.0.113.9";

    @DynamicPropertySource
    static void otpProps(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.otp.hmac-key-b64", () -> KEY);
        r.add("tazzzo.customer-auth.otp.ttl-seconds", () -> "300");
        r.add("tazzzo.customer-auth.otp.resend-cooldown-seconds", () -> "30");
        r.add("tazzzo.customer-auth.otp.max-attempts", () -> "3");
        r.add("tazzzo.customer-auth.otp.grant-ttl-seconds", () -> "300");
        for (String bucket : List.of("request-ip", "request-phone", "verify-ip", "verify-challenge")) {
            r.add("tazzzo.customer-auth.otp." + bucket + ".capacity", () -> "1000");
            r.add("tazzzo.customer-auth.otp." + bucket + ".refill-per-second", () -> "1000");
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean RateLimitStore alwaysAllowRateLimitStore() {
            return (buckets, cost) -> new Admission.Allowed(List.of());
        }

        @Bean @Primary Clock mutableClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return CLOCK_NOW.get(); }
            };
        }
    }

    static class CapturingProvider implements OtpDeliveryProvider {
        final Map<String, String> lastOtpByPhone = new ConcurrentHashMap<>();

        @Override public void sendLoginOtp(Phone phone, String otp, Duration expiresIn) {
            lastOtpByPhone.put(phone.value(), otp);
        }
    }

    @Autowired OtpChallengeRepository challenges;
    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired Clock clock;

    private final CapturingProvider provider = new CapturingProvider();
    private RetryInjectingTx retryTx;
    private OtpService service;

    @BeforeEach
    void setUp() {
        CLOCK_NOW.set(Instant.parse("2099-06-01T00:00:00Z"));
        provider.lastOtpByPhone.clear();
        retryTx = new RetryInjectingTx(client);
        OtpAuthProperties p = new OtpAuthProperties();
        p.setHmacKeyB64(KEY);
        p.setTtlSeconds(300);
        p.setResendCooldownSeconds(30);
        p.setMaxAttempts(3);
        p.setGrantTtlSeconds(300);
        p.getRequestIp().setCapacity(1000);
        p.getRequestIp().setRefillPerSecond(1000);
        p.getRequestPhone().setCapacity(1000);
        p.getRequestPhone().setRefillPerSecond(1000);
        p.getVerifyIp().setCapacity(1000);
        p.getVerifyIp().setRefillPerSecond(1000);
        p.getVerifyChallenge().setCapacity(1000);
        p.getVerifyChallenge().setRefillPerSecond(1000);
        OtpRateLimiter limiter = new OtpRateLimiter((buckets, cost) -> new Admission.Allowed(List.of()), p);
        service = new OtpService(challenges, grants, new OtpVerifierCodec(p), p, clock, retryTx,
                new FixedObjectProvider<>(limiter), new FixedObjectProvider<OtpDeliveryProvider>(provider));
    }

    private Document challenge(String id) {
        return db.getCollection(OtpChallengeRepository.COLLECTION).find(Filters.eq("_id", id)).first();
    }

    private long grantCount(String challengeId) {
        return db.getCollection(OtpVerifiedGrantRepository.COLLECTION)
                .countDocuments(Filters.eq("challengeId", challengeId));
    }

    private static void assertReason(Throwable t, OtpFailure.Reason reason) {
        assertThat(t).isInstanceOf(OtpFailure.class);
        assertThat(((OtpFailure) t).reason()).isEqualTo(reason);
    }

    // ---------- verify ----------

    @Test void verify_stale_first_attempt_success_cannot_escape_when_the_retry_loses_the_cas() {
        String phone = "9876541001";
        OtpRequestResult req = service.request(phone, IP);
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        // attempt 1: CAS wins + grant inserted + callback returns VERIFIED, then the commit "fails
        // transiently" and attempt 1 is rolled back. Before attempt 2 a competitor supersedes the
        // challenge, so attempt 2 loses the CAS.
        retryTx.arm(1, n -> {
            if (n == 2) {
                db.getCollection(OtpChallengeRepository.COLLECTION).updateOne(Filters.eq("_id", req.challengeId()),
                        Updates.set("status", "SUPERSEDED"));
            }
        });

        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, IP))
                .satisfies(t -> assertReason(t, OtpFailure.Reason.INVALID));

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(retryTx.attemptResults()).extracting(Object::toString).containsExactly("VERIFIED", "NOT_VERIFIED");
        assertThat(grantCount(req.challengeId())).as("attempt 1's grant was rolled back").isZero();
        Document c = challenge(req.challengeId());
        assertThat(c.getString("status")).isEqualTo("SUPERSEDED");
        assertThat(c.get("grantId")).as("no grant id survived on the challenge").isNull();
    }

    @Test void verify_retry_whose_second_attempt_succeeds_returns_the_committed_grant() {
        String phone = "9876541002";
        OtpRequestResult req = service.request(phone, IP);
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        retryTx.arm(1);
        OtpVerifyResult result = service.verify(req.challengeId(), otp, IP);

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(retryTx.attemptResults()).extracting(Object::toString).containsExactly("VERIFIED", "VERIFIED");
        assertThat(grantCount(req.challengeId())).as("no duplicate grant").isEqualTo(1);
        Document grant = db.getCollection(OtpVerifiedGrantRepository.COLLECTION)
                .find(Filters.eq("challengeId", req.challengeId())).first();
        assertThat(grant.getString("_id")).as("returned grant == the committed grant").isEqualTo(result.grantId());
        Document c = challenge(req.challengeId());
        assertThat(c.getString("status")).isEqualTo("VERIFIED");
        assertThat(c.getString("grantId")).as("no challenge/grant split-brain").isEqualTo(result.grantId());
        assertThat(result.challengeId()).isEqualTo(req.challengeId());
        // one-time: the same OTP can never verify again
        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, IP))
                .satisfies(t -> assertReason(t, OtpFailure.Reason.INVALID));
        assertThat(grantCount(req.challengeId())).isEqualTo(1);
    }

    @Test void verify_survives_two_consecutive_rolled_back_attempts_with_one_stable_grant_id() {
        String phone = "9876541003";
        OtpRequestResult req = service.request(phone, IP);
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        retryTx.arm(2);
        OtpVerifyResult result = service.verify(req.challengeId(), otp, IP);

        assertThat(retryTx.attempts()).isEqualTo(3);
        assertThat(grantCount(req.challengeId())).isEqualTo(1);
        assertThat(challenge(req.challengeId()).getString("grantId")).isEqualTo(result.grantId());
    }

    @Test void verify_wrong_and_expired_outcomes_are_unchanged_by_the_new_transaction_shape() {
        String phone = "9876541004";
        OtpRequestResult req = service.request(phone, IP);
        String otp = provider.lastOtpByPhone.get("+91" + phone);
        String wrong = otp.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> service.verify(req.challengeId(), wrong, IP))
                .satisfies(t -> assertReason(t, OtpFailure.Reason.INVALID));
        assertThat(grantCount(req.challengeId())).isZero();

        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(301));
        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, IP))
                .satisfies(t -> assertReason(t, OtpFailure.Reason.EXPIRED));
        assertThat(grantCount(req.challengeId())).isZero();
    }

    // ---------- requestOtp ----------

    @Test void request_resend_retry_returns_the_committed_activation_and_leaves_one_active_challenge() {
        String phone = "9876541011";
        OtpRequestResult first = service.request(phone, IP);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(60)); // cooldown elapsed -> a real resend

        retryTx.arm(1);
        OtpRequestResult second = service.request(phone, IP);

        assertThat(retryTx.attempts()).isEqualTo(2);
        assertThat(second.challengeId()).isNotEqualTo(first.challengeId());
        assertThat(second.expiresInSeconds()).isEqualTo(300);
        assertThat(second.resendAfterSeconds()).isEqualTo(30);
        assertThat(challenge(first.challengeId()).getString("status")).isEqualTo("SUPERSEDED");
        assertThat(challenge(second.challengeId()).getString("status")).isEqualTo("ACTIVE");
        assertThat(db.getCollection(OtpChallengeRepository.COLLECTION)
                .countDocuments(Filters.and(Filters.eq("phoneNormalized", "+91" + phone), Filters.eq("active", true))))
                .as("exactly one usable code").isEqualTo(1);
    }

    @Test void request_retry_whose_activation_no_longer_applies_fails_and_does_not_supersede_the_old_code() {
        String phone = "9876541012";
        OtpRequestResult first = service.request(phone, IP);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(60));

        // attempt 1 applies both writes (supersede old + activate new) and is rolled back; before
        // attempt 2 the pending replacement is retired, so attempt 2's activation does not apply.
        retryTx.arm(1, n -> {
            if (n == 2) {
                db.getCollection(OtpChallengeRepository.COLLECTION).updateMany(
                        Filters.and(Filters.eq("phoneNormalized", "+91" + phone), Filters.eq("status", "PENDING_DELIVERY")),
                        Updates.set("status", "DELIVERY_FAILED"));
            }
        });
        assertThatThrownBy(() -> service.request(phone, IP))
                .satisfies(t -> assertReason(t, OtpFailure.Reason.UNAVAILABLE));

        Document old = challenge(first.challengeId());
        assertThat(old.getString("status")).as("attempt 1's supersede was rolled back").isEqualTo("ACTIVE");
        assertThat(old.getBoolean("active")).isTrue();
    }
}
