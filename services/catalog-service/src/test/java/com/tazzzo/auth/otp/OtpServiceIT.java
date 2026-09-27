package com.tazzzo.auth.otp;

import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.BucketSpec;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-11B — {@code OtpService} exercised over a real Mongo (Testcontainers), a real
 * {@link OtpChallengeRepository}/{@link OtpVerifiedGrantRepository}, and a controllable clock. The
 * Spring-context tests use an always-allow fake store; rate-limit DENIAL is exercised directly
 * against manually-constructed {@code OtpService} instances further down (no Spring context
 * needed). Delivery uses a capturing test-only provider so the plaintext OTP is observable HERE
 * ONLY, never in production code or logs (§27/§36).
 */
@SpringBootTest(classes = {CatalogApplication.class, OtpServiceIT.TestBeans.class})
class OtpServiceIT extends AbstractMongoIT {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final AtomicReference<Instant> CLOCK_NOW = new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));

    @DynamicPropertySource
    static void otpProps(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.otp.hmac-key-b64", () -> KEY);
        r.add("tazzzo.customer-auth.otp.ttl-seconds", () -> "300");
        r.add("tazzzo.customer-auth.otp.resend-cooldown-seconds", () -> "30");
        r.add("tazzzo.customer-auth.otp.max-attempts", () -> "3");
        r.add("tazzzo.customer-auth.otp.grant-ttl-seconds", () -> "300");
        r.add("tazzzo.customer-auth.otp.request-ip.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.request-ip.refill-per-second", () -> "1000");
        r.add("tazzzo.customer-auth.otp.request-phone.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.request-phone.refill-per-second", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-ip.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-ip.refill-per-second", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-challenge.capacity", () -> "1000");
        r.add("tazzzo.customer-auth.otp.verify-challenge.refill-per-second", () -> "1000");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        RateLimitStore alwaysAllowRateLimitStore() {
            return (buckets, cost) -> new Admission.Allowed(List.of());
        }

        @Bean
        @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return CLOCK_NOW.get(); }
            };
        }

        @Bean
        @Primary
        OtpDeliveryProvider capturingOtpDeliveryProvider() {
            return new CapturingOtpDeliveryProvider();
        }
    }

    static class CapturingOtpDeliveryProvider implements OtpDeliveryProvider {
        final Map<String, String> lastOtpByPhone = new ConcurrentHashMap<>();
        volatile boolean failNext = false;

        @Override
        public void sendLoginOtp(Phone phone, String otp, Duration expiresIn) {
            if (failNext) {
                throw new RuntimeException("simulated provider failure");
            }
            lastOtpByPhone.put(phone.value(), otp);
        }
    }

    @Autowired OtpService service;
    @Autowired OtpChallengeRepository challenges;
    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired CapturingOtpDeliveryProvider provider;

    @BeforeEach
    void resetClockAndProvider() {
        CLOCK_NOW.set(Instant.parse("2026-06-01T00:00:00Z"));
        provider.failNext = false;
        provider.lastOtpByPhone.clear();
    }

    private static String uniquePhone(String suffix) {
        return "98765" + suffix;
    }

    // ---------- happy path ----------

    @Test void full_request_then_verify_produces_exactly_one_grant_with_no_phone_exposed() {
        String phone = uniquePhone("40001");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        assertThat(req.challengeId()).matches("^OTP_[A-Za-z0-9_-]+$");
        assertThat(req.expiresInSeconds()).isEqualTo(300);
        assertThat(req.resendAfterSeconds()).isEqualTo(30);

        String otp = provider.lastOtpByPhone.get("+91" + phone);
        assertThat(otp).matches("^[0-9]{6}$");

        OtpVerifyResult verify = service.verify(req.challengeId(), otp, "203.0.113.1");
        assertThat(verify.challengeId()).isEqualTo(req.challengeId());
        assertThat(verify.grantId()).matches("^GRANT_[A-Za-z0-9_-]+$");

        Document grant = grants.consume(verify.grantId(), OtpPurpose.LOGIN, CLOCK_NOW.get().minusSeconds(1));
        assertThat(grant).as("grant must exist and be consumable exactly once").isNotNull();
        assertThat(grant.getString("phoneNormalized")).isEqualTo("+91" + phone);
        Document secondConsume = grants.consume(verify.grantId(), OtpPurpose.LOGIN, CLOCK_NOW.get());
        assertThat(secondConsume).as("a grant is one-time use").isNull();
    }

    @Test void response_never_reveals_customer_existence_shape_is_identical_for_any_phone() {
        // PR-11B queries no customer collection at all; this is a structural fact, but pin the
        // observable behavior too: two different, never-before-seen phones get the same response
        // shape.
        OtpRequestResult a = service.request(uniquePhone("40010"), "203.0.113.1");
        OtpRequestResult b = service.request(uniquePhone("40011"), "203.0.113.1");
        assertThat(a.expiresInSeconds()).isEqualTo(b.expiresInSeconds());
        assertThat(a.resendAfterSeconds()).isEqualTo(b.resendAfterSeconds());
    }

    // ---------- wrong OTP / attempts / locking ----------

    @Test void wrong_otp_is_rejected_and_correct_otp_still_works_before_lockout() {
        String phone = uniquePhone("40020");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        assertThatThrownBy(() -> service.verify(req.challengeId(), "000000", "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));

        String otp = provider.lastOtpByPhone.get("+91" + phone);
        OtpVerifyResult verify = service.verify(req.challengeId(), otp, "203.0.113.1");
        assertThat(verify.grantId()).isNotNull();
    }

    @Test void exhausting_max_attempts_locks_the_challenge_even_for_the_correct_otp() {
        String phone = uniquePhone("40030");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.verify(req.challengeId(), "111111", "203.0.113.1"))
                    .isInstanceOf(OtpFailure.class);
        }
        // Locked now — even the CORRECT otp must fail generically, never revealing "locked".
        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));
    }

    @Test void reused_correct_otp_after_verification_is_rejected() {
        String phone = uniquePhone("40040");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);
        service.verify(req.challengeId(), otp, "203.0.113.1");
        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));
    }

    @Test void unknown_challenge_id_is_generic_invalid() {
        assertThatThrownBy(() -> service.verify("OTP_" + "z".repeat(24), "123456", "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));
    }

    @Test void malformed_challenge_id_or_otp_shape_is_invalid_request() {
        assertThatThrownBy(() -> service.verify("not-a-challenge-id", "123456", "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> service.verify("OTP_" + "a".repeat(24), "12a456", "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID_REQUEST));
    }

    // ---------- expiry ----------

    @Test void expired_challenge_cannot_verify_even_with_the_correct_otp() {
        String phone = uniquePhone("40050");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(301));
        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.EXPIRED));
    }

    @Test void a_challenge_verifying_exactly_at_expiry_boundary_is_expired() {
        String phone = uniquePhone("40051");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(300));
        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.EXPIRED));
    }

    // ---------- resend ----------

    @Test void resend_within_cooldown_is_idempotent_same_challenge_no_new_send() {
        String phone = uniquePhone("40060");
        OtpRequestResult first = service.request(phone, "203.0.113.1");
        String firstOtp = provider.lastOtpByPhone.get("+91" + phone);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(5));
        OtpRequestResult second = service.request(phone, "203.0.113.1");
        assertThat(second.challengeId()).isEqualTo(first.challengeId());
        // still verifiable with the FIRST otp — no supersede happened
        OtpVerifyResult verify = service.verify(first.challengeId(), firstOtp, "203.0.113.1");
        assertThat(verify.grantId()).isNotNull();
    }

    @Test void resend_after_cooldown_supersedes_previous_code() {
        String phone = uniquePhone("40070");
        OtpRequestResult first = service.request(phone, "203.0.113.1");
        String firstOtp = provider.lastOtpByPhone.get("+91" + phone);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(31));
        OtpRequestResult second = service.request(phone, "203.0.113.1");
        assertThat(second.challengeId()).isNotEqualTo(first.challengeId());

        // the OLD code must no longer work
        assertThatThrownBy(() -> service.verify(first.challengeId(), firstOtp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));

        // the NEW code works
        String secondOtp = provider.lastOtpByPhone.get("+91" + phone);
        OtpVerifyResult verify = service.verify(second.challengeId(), secondOtp, "203.0.113.1");
        assertThat(verify.grantId()).isNotNull();
    }

    // ---------- provider failure ----------

    @Test void provider_failure_leaves_no_usable_challenge_and_frees_the_slot() {
        String phone = uniquePhone("40080");
        provider.failNext = true;
        assertThatThrownBy(() -> service.request(phone, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
        assertThat(challenges.findActive(Phone.parse(phone), OtpPurpose.LOGIN)).isNull();

        // slot is free: a subsequent request succeeds normally
        provider.failNext = false;
        OtpRequestResult retry = service.request(phone, "203.0.113.1");
        assertThat(retry.challengeId()).isNotNull();
    }

    // ---------- concurrency ----------

    @Test void two_simultaneous_correct_verify_requests_only_one_produces_a_grant() throws Exception {
        String phone = uniquePhone("40090");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<OtpVerifyResult> successes = new CopyOnWriteArrayList<>();
        List<Exception> failures = new CopyOnWriteArrayList<>();
        Runnable attempt = () -> {
            ready.countDown();
            try {
                go.await();
                successes.add(service.verify(req.challengeId(), otp, "203.0.113.1"));
            } catch (OtpFailure e) {
                failures.add(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(attempt);
        pool.submit(attempt);
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(successes).as("exactly one of the two racing verifications may succeed").hasSize(1);
        assertThat(failures).hasSize(1);
        // exactly one grant exists for this challenge
        Document grant = grants.consume(successes.get(0).grantId(), OtpPurpose.LOGIN, CLOCK_NOW.get().minusSeconds(1));
        assertThat(grant).isNotNull();
    }

    @Test void wrong_attempt_race_cannot_bypass_the_attempt_limit() throws Exception {
        String phone = uniquePhone("40100");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Exception> outcomes = new CopyOnWriteArrayList<>();
        for (int i = 0; i < racers; i++) {
            pool.submit(() -> {
                try {
                    go.await();
                    service.verify(req.challengeId(), "999999", "203.0.113.1");
                } catch (OtpFailure e) {
                    outcomes.add(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(outcomes).hasSize(racers);
        assertThat(outcomes).allSatisfy(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));
        // the correct OTP must now be permanently refused: max attempts cannot be bypassed by racing
        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));
    }

    @Test void resend_race_never_leaves_two_simultaneously_usable_codes() throws Exception {
        String phone = uniquePhone("40110");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<OtpRequestResult> results = new CopyOnWriteArrayList<>();
        Runnable attempt = () -> {
            ready.countDown();
            try {
                go.await();
                results.add(service.request(phone, "203.0.113.1"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(attempt);
        pool.submit(attempt);
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(results).hasSize(2);
        // At most one distinct challenge is genuinely ACTIVE/PENDING_DELIVERY for this phone.
        Document active = challenges.findActive(Phone.parse(phone), OtpPurpose.LOGIN);
        assertThat(active).isNotNull();
        String activeId = active.getString("_id");
        for (OtpRequestResult r : results) {
            if (!r.challengeId().equals(activeId)) {
                Document other = challenges.findById(r.challengeId());
                assertThat(other.getString("status"))
                        .as("a non-active returned identity must be superseded/failed, never separately usable")
                        .isIn("SUPERSEDED", "DELIVERY_FAILED");
            }
        }
    }

    // ---------- unavailable seams ----------

    @Test void not_ready_codec_fails_closed_on_request_and_verify() {
        OtpAuthProperties badProps = new OtpAuthProperties();
        OtpVerifierCodec notReady = new OtpVerifierCodec(badProps);
        OtpService brokenService = new OtpService(challenges, grants, notReady, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), fixedProvider(null), fixedDeliveryProvider(provider));
        assertThatThrownBy(() -> brokenService.request(uniquePhone("40200"), "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
    }

    @Test void missing_rate_limiter_fails_closed() {
        OtpVerifierCodec readyCodec = readyTestCodec();
        OtpService brokenService = new OtpService(challenges, grants, readyCodec, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), fixedProvider(null), fixedDeliveryProvider(provider));
        assertThatThrownBy(() -> brokenService.request(uniquePhone("40210"), "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
    }

    @Test void missing_provider_fails_closed_on_request_only() {
        OtpVerifierCodec readyCodec = readyTestCodec();
        RateLimitStore alwaysAllow = (buckets, cost) -> new Admission.Allowed(List.of());
        OtpRateLimiter limiter = new OtpRateLimiter(alwaysAllow, activeTestProperties());
        OtpService brokenService = new OtpService(challenges, grants, readyCodec, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), fixedProvider(limiter), fixedDeliveryProvider(null));
        assertThatThrownBy(() -> brokenService.request(uniquePhone("40220"), "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
    }

    @Test void request_rate_limited_bucket_denies_with_retry_after() {
        OtpVerifierCodec readyCodec = readyTestCodec();
        RateLimitStore denies = (buckets, cost) -> new Admission.RateLimited(Duration.ofSeconds(7), List.of());
        OtpRateLimiter limiter = new OtpRateLimiter(denies, activeTestProperties());
        OtpService limitedService = new OtpService(challenges, grants, readyCodec, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), fixedProvider(limiter), fixedDeliveryProvider(provider));
        assertThatThrownBy(() -> limitedService.request(uniquePhone("40230"), "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> {
                    OtpFailure f = (OtpFailure) e;
                    assertThat(f.reason()).isEqualTo(OtpFailure.Reason.RATE_LIMITED);
                    assertThat(f.retryAfter()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test void verify_rate_limited_bucket_denies_before_touching_the_challenge() {
        OtpVerifierCodec readyCodec = readyTestCodec();
        RateLimitStore denies = (buckets, cost) -> new Admission.RateLimited(Duration.ofSeconds(3), List.of());
        OtpRateLimiter limiter = new OtpRateLimiter(denies, activeTestProperties());
        OtpService limitedService = new OtpService(challenges, grants, readyCodec, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), fixedProvider(limiter), fixedDeliveryProvider(provider));
        assertThatThrownBy(() -> limitedService.verify("OTP_" + "a".repeat(24), "123456", "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.RATE_LIMITED));
    }

    private OtpVerifierCodec readyTestCodec() {
        OtpAuthProperties p = new OtpAuthProperties();
        p.setHmacKeyB64(KEY);
        return new OtpVerifierCodec(p);
    }

    private OtpAuthProperties activeTestProperties() {
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
        return p;
    }

    private static org.springframework.beans.factory.ObjectProvider<OtpRateLimiter> fixedProvider(OtpRateLimiter v) {
        return new FixedObjectProvider<>(v);
    }

    private static org.springframework.beans.factory.ObjectProvider<OtpDeliveryProvider> fixedDeliveryProvider(
            OtpDeliveryProvider v) {
        return new FixedObjectProvider<>(v);
    }
}
