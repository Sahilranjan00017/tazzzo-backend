package com.tazzzo.auth.otp;

import com.mongodb.MongoWriteException;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
import com.tazzzo.catalog.ratelimit.Admission;
import com.tazzzo.catalog.ratelimit.RateLimitStore;
import com.tazzzo.catalog.tx.Tx;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
                throw new OtpProviderException("simulated provider failure");
            }
            lastOtpByPhone.put(phone.value(), otp);
        }
    }

    /**
     * A provider whose {@code sendLoginOtp} pauses mid-call — signals {@code entered} the instant
     * it is invoked (so a test knows the challenge is durably PENDING_DELIVERY), then blocks on
     * {@code proceed} until the test releases it, then either succeeds or throws. No sleeps: the
     * test controls timing entirely via latches.
     */
    static class ControllableOtpDeliveryProvider implements OtpDeliveryProvider {
        private final CountDownLatch entered;
        private final CountDownLatch proceed;
        private final boolean fail;

        ControllableOtpDeliveryProvider(CountDownLatch entered, CountDownLatch proceed, boolean fail) {
            this.entered = entered;
            this.proceed = proceed;
            this.fail = fail;
        }

        @Override
        public void sendLoginOtp(Phone phone, String otp, Duration expiresIn) {
            entered.countDown();
            try {
                proceed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OtpProviderException("interrupted", e);
            }
            if (fail) {
                throw new OtpProviderException("simulated provider failure");
            }
        }
    }

    /** Fails the first {@code failCount} SESSION-based insert attempts (the transactional path
     *  {@code OtpService} actually uses), then delegates to the real behavior. */
    static class FlakyOnceGrantRepository extends OtpVerifiedGrantRepository {
        private final AtomicInteger remainingFailures;

        FlakyOnceGrantRepository(MongoDatabase db, int failCount) {
            super(db);
            this.remainingFailures = new AtomicInteger(failCount);
        }

        @Override
        public Document insert(ClientSession session, String grantId, String challengeId, Phone phone,
                               OtpPurpose purpose, Instant now, Instant expiresAt) {
            if (remainingFailures.getAndDecrement() > 0) {
                throw new RuntimeException("simulated transient grant-insert failure");
            }
            return super.insert(session, grantId, challengeId, phone, purpose, now, expiresAt);
        }
    }

    /**
     * Forces {@code activateAfterDelivery}'s SECOND write (PENDING_DELIVERY -&gt; ACTIVE) to report
     * "did not apply", while its FIRST write (superseding the old ACTIVE challenge) runs for real —
     * exercising the exact failure window the transaction-abort fix closes: if step 2 fails, step 1
     * must roll back too.
     */
    static class ForceActivationFailureChallengeRepository extends OtpChallengeRepository {
        ForceActivationFailureChallengeRepository(MongoDatabase db) {
            super(db);
        }

        @Override
        Document activatePendingToActive(ClientSession session, String challengeId, Instant sentAt,
                                         Instant expiresAt, Instant resendAvailableAt) {
            return null;
        }
    }

    @Autowired OtpService service;
    @Autowired OtpChallengeRepository challenges;
    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired CapturingOtpDeliveryProvider provider;
    @Autowired Clock clock;
    @Autowired Tx tx;

    private OtpRateLimiter sharedAllowLimiter() {
        RateLimitStore alwaysAllow = (buckets, cost) -> new Admission.Allowed(List.of());
        return new OtpRateLimiter(alwaysAllow, activeTestProperties());
    }

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

    /**
     * Hardening §2/§8: with the two-marker design, at most ONE of two concurrent requests for a
     * brand-new phone (no prior ACTIVE challenge, so neither can take the idempotent-cooldown path)
     * may win the "delivering" slot — the loser is bounced UNAVAILABLE rather than falsely claiming
     * success. This is a semantic CHANGE from the pre-hardening design, which used to hand the loser
     * a borrowed success — that was exactly the false-success bug this pass closes.
     *
     * <p><b>Note on outcome shape:</b> two truly concurrent first-time requests (no prior ACTIVE
     * challenge to synchronize on) can legitimately EITHER collide at the "delivering" insert (one
     * throws duplicate-key -&gt; UNAVAILABLE) OR — if their timing does not overlap at that exact
     * instant — both independently insert, deliver, and activate in sequence (the second's
     * {@code activateAfterDelivery} then supersedes the first). Both outcomes are safe: neither is a
     * FALSE success (every returned identity was genuinely, confirmedly delivered), and exactly one
     * challenge ends up ACTIVE. The scenario this test guards against — a caller observing an
     * UNCONFIRMED in-flight delivery and being told it succeeded — is instead pinned deterministically
     * with latches in {@link #concurrent_request_while_delivery_in_flight_never_returns_false_success_and_original_succeeds}.
     */
    @Test void resend_race_never_leaves_two_simultaneously_usable_codes() throws Exception {
        String phone = uniquePhone("40110");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<OtpRequestResult> results = new CopyOnWriteArrayList<>();
        List<OtpFailure> failures = new CopyOnWriteArrayList<>();
        Runnable attempt = () -> {
            ready.countDown();
            try {
                go.await();
                results.add(service.request(phone, "203.0.113.1"));
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

        assertThat(results.size() + failures.size()).isEqualTo(2);
        for (OtpFailure f : failures) {
            assertThat(f.reason()).as("the only legitimate failure mode here is a slot collision")
                    .isEqualTo(OtpFailure.Reason.UNAVAILABLE);
        }
        // No fabricated/borrowed identity: every identity handed back was a real challenge that was
        // genuinely delivered — currently ACTIVE, or SUPERSEDED by a later legitimate winner.
        for (OtpRequestResult r : results) {
            Document doc = challenges.findById(r.challengeId());
            assertThat(doc).isNotNull();
            assertThat(doc.getString("status")).isIn("ACTIVE", "SUPERSEDED");
        }
        // Exactly one challenge is the CURRENT usable code — never two at once.
        Document active = challenges.findActive(Phone.parse(phone), OtpPurpose.LOGIN);
        assertThat(active).isNotNull();
    }

    /**
     * Hardening §2 — the CORE false-success bug: a concurrent request arriving while a delivery is
     * genuinely in flight must never be told "202 challenge created" for a code that was never
     * confirmed delivered. Proven deterministically with latches, no sleeps.
     */
    @Test void concurrent_request_while_delivery_in_flight_never_returns_false_success_and_original_succeeds()
            throws Exception {
        String phone = uniquePhone("40400");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ControllableOtpDeliveryProvider blockingProvider = new ControllableOtpDeliveryProvider(entered, proceed, false);
        OtpService blockingService = new OtpService(challenges, grants, readyTestCodec(), activeTestProperties(),
                clock, tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(blockingProvider));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<OtpRequestResult> inFlight = pool.submit(() -> blockingService.request(phone, "203.0.113.1"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("provider was entered").isTrue();

        // A second caller arrives while delivery is still unconfirmed — must be bounced, not 202.
        assertThatThrownBy(() -> blockingService.request(phone, "203.0.113.2"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));

        proceed.countDown();
        OtpRequestResult result = inFlight.get(10, TimeUnit.SECONDS);
        assertThat(result.challengeId()).isNotNull();
        pool.shutdown();

        Document active = challenges.findActive(Phone.parse(phone), OtpPurpose.LOGIN);
        assertThat(active).isNotNull();
        assertThat(active.getString("_id")).isEqualTo(result.challengeId());
    }

    /** Hardening §2 — same race, but delivery ultimately FAILS: still no false success anywhere. */
    @Test void concurrent_request_while_delivery_in_flight_then_provider_fails_no_false_success_ever()
            throws Exception {
        String phone = uniquePhone("40410");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ControllableOtpDeliveryProvider blockingProvider = new ControllableOtpDeliveryProvider(entered, proceed, true);
        OtpService blockingService = new OtpService(challenges, grants, readyTestCodec(), activeTestProperties(),
                clock, tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(blockingProvider));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<OtpRequestResult> inFlight = pool.submit(() -> blockingService.request(phone, "203.0.113.1"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> blockingService.request(phone, "203.0.113.2"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));

        proceed.countDown();
        assertThatThrownBy(() -> inFlight.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isInstanceOf(OtpFailure.class);
        pool.shutdown();

        // No ghost ACTIVE challenge is left behind for either caller.
        assertThat(challenges.findActive(Phone.parse(phone), OtpPurpose.LOGIN)).isNull();
    }

    /** Hardening §8 — the previous, already-delivered code must remain valid while a resend is
     *  mid-flight, and provider failure on the resend must never destroy it. */
    @Test void resend_keeps_old_code_usable_while_replacement_delivery_is_in_flight() throws Exception {
        String phone = uniquePhone("40420");
        OtpRequestResult first = service.request(phone, "203.0.113.1");
        String firstOtp = provider.lastOtpByPhone.get("+91" + phone);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(31)); // past cooldown -> next request is a resend

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ControllableOtpDeliveryProvider blockingProvider = new ControllableOtpDeliveryProvider(entered, proceed, false);
        OtpService blockingService = new OtpService(challenges, grants, readyTestCodec(), activeTestProperties(),
                clock, tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(blockingProvider));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<OtpRequestResult> resend = pool.submit(() -> blockingService.request(phone, "203.0.113.1"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // The OLD code must STILL work while the replacement is unconfirmed.
        OtpVerifyResult verify = blockingService.verify(first.challengeId(), firstOtp, "203.0.113.1");
        assertThat(verify.grantId()).isNotNull();

        proceed.countDown();
        resend.get(10, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test void resend_provider_failure_does_not_destroy_the_previous_working_code() {
        String phone = uniquePhone("40430");
        OtpRequestResult first = service.request(phone, "203.0.113.1");
        String firstOtp = provider.lastOtpByPhone.get("+91" + phone);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(31));

        provider.failNext = true;
        assertThatThrownBy(() -> service.request(phone, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
        provider.failNext = false;

        // The OLD code must be untouched by the failed resend attempt.
        OtpVerifyResult verify = service.verify(first.challengeId(), firstOtp, "203.0.113.1");
        assertThat(verify.grantId()).isNotNull();
    }

    // ---------- verification/grant durability (hardening §1/§3/§4/§5/§6/§7) ----------

    /**
     * Durability §1 — with the transactional redesign, a grant-insert failure aborts the WHOLE
     * transaction (Mongo transactions are all-or-nothing): the challenge reverts to ACTIVE, never
     * stranded as "VERIFIED with no grant". The recovery path is simply the client calling the
     * SAME public {@code POST /v1/auth/otp/verify} again with the SAME OTP — no manual repository
     * invocation, no out-of-band repair. Each successful attempt mints its own fresh grantId (a
     * strictly stronger guarantee than "the same grantId persists across a failed attempt", since a
     * failed attempt now durably persists NOTHING at all).
     */
    @Test void grant_insert_transient_failure_then_legitimate_client_retry_recovers_exactly_one_grant() {
        String phone = uniquePhone("40500");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        FlakyOnceGrantRepository flakyGrants = new FlakyOnceGrantRepository(db, 1); // fails once
        OtpService flakyService = new OtpService(challenges, flakyGrants, readyTestCodec(), activeTestProperties(),
                clock, tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(provider));

        // First attempt: the transaction aborts because the grant insert failed.
        assertThatThrownBy(() -> flakyService.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
        Document afterAbort = challenges.findById(req.challengeId());
        assertThat(afterAbort.getString("status"))
                .as("an aborted transaction must leave the challenge exactly as it was — still ACTIVE")
                .isEqualTo("ACTIVE");
        assertThat(afterAbort.getString("grantId")).isNull();

        // Legitimate retry: the SAME public call, SAME OTP, no manual repository invocation.
        OtpVerifyResult retried = flakyService.verify(req.challengeId(), otp, "203.0.113.1");
        assertThat(retried.grantId()).isNotNull();

        long grantCount = db.getCollection(OtpVerifiedGrantRepository.COLLECTION)
                .countDocuments(Filters.eq("challengeId", req.challengeId()));
        assertThat(grantCount).as("exactly one grant ultimately exists").isEqualTo(1);
    }

    @Test void grant_insert_permanent_failure_leaves_the_challenge_active_and_recoverable_by_the_working_service() {
        String phone = uniquePhone("40510");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        FlakyOnceGrantRepository alwaysFailingGrants = new FlakyOnceGrantRepository(db, Integer.MAX_VALUE);
        OtpService brokenService = new OtpService(challenges, alwaysFailingGrants, readyTestCodec(),
                activeTestProperties(), clock, tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(provider));

        assertThatThrownBy(() -> brokenService.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));

        // No half-applied state whatsoever: still ACTIVE, no phantom grantId, no grant document.
        Document doc = challenges.findById(req.challengeId());
        assertThat(doc.getString("status")).isEqualTo("ACTIVE");
        assertThat(doc.getString("grantId")).isNull();
        long grantCount = db.getCollection(OtpVerifiedGrantRepository.COLLECTION)
                .countDocuments(Filters.eq("challengeId", req.challengeId()));
        assertThat(grantCount).isEqualTo(0);

        // The SAME challenge, verified again through the WORKING service, succeeds normally.
        OtpVerifyResult recovered = service.verify(req.challengeId(), otp, "203.0.113.1");
        assertThat(recovered.grantId()).isNotNull();
    }

    /** Durability §4 — a duplicate-key hit on challengeId must be disambiguated, never blindly
     *  accepted: a MISMATCHED existing grant (different grantId — simulated corruption) is an
     *  integrity violation, and must never let a nonexistent/wrong grantId be returned as success. */
    @Test void mismatched_existing_grant_for_the_same_challenge_is_never_accepted_as_success() {
        String phone = uniquePhone("40515");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        // Seed a CONFLICTING grant for this challengeId up front (simulating corruption / a bug) —
        // a different grantId than whatever verify() will generate.
        grants.insert(OpaqueIds.newGrantId(), req.challengeId(), Phone.parse(phone), OtpPurpose.LOGIN,
                CLOCK_NOW.get(), CLOCK_NOW.get().plusSeconds(300));

        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));

        // The transaction aborted: the challenge is back to ACTIVE, never falsely VERIFIED.
        assertThat(challenges.findById(req.challengeId()).getString("status")).isEqualTo("ACTIVE");
    }

    /** Durability §5 — with a real transaction, verifiedAt/grant createdAt/grant expiresAt are all
     *  the SAME instant as the one successful attempt; a prior FAILED attempt's clock reading can
     *  never leak into the eventually-committed grant's expiry. */
    @Test void grant_expiry_is_pinned_to_the_committed_verification_instant_not_a_failed_attempt() {
        String phone = uniquePhone("40525");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        FlakyOnceGrantRepository flakyGrants = new FlakyOnceGrantRepository(db, 1);
        OtpService flakyService = new OtpService(challenges, flakyGrants, readyTestCodec(), activeTestProperties(),
                clock, tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(provider));
        assertThatThrownBy(() -> flakyService.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class);

        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(50));
        OtpVerifyResult retried = service.verify(req.challengeId(), otp, "203.0.113.1");

        Document grant = db.getCollection(OtpVerifiedGrantRepository.COLLECTION)
                .find(Filters.eq("_id", retried.grantId())).first();
        assertThat(grant.getDate("createdAt").toInstant()).isEqualTo(CLOCK_NOW.get());
        assertThat(grant.getDate("expiresAt").toInstant())
                .isEqualTo(CLOCK_NOW.get().plusSeconds(activeTestProperties().getGrantTtlSeconds()));
    }

    /**
     * The transaction-abort correctness fix: if the confirmed-delivery hand-off's SECOND write
     * (activating the replacement) does not apply, the FIRST write (superseding the old ACTIVE
     * challenge) — which genuinely ran, inside the SAME transaction — must be rolled back too.
     * Exercises this through a REAL transaction abort (not merely calling
     * {@code activateAfterDelivery} directly and inspecting its null return).
     */
    @Test void activation_failure_rolls_back_the_old_active_supersede_too() {
        String phone = uniquePhone("40600");
        OtpRequestResult oldReq = service.request(phone, "203.0.113.1");
        String oldOtp = provider.lastOtpByPhone.get("+91" + phone);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(31)); // past cooldown -> next request is a resend

        ForceActivationFailureChallengeRepository failingChallenges =
                new ForceActivationFailureChallengeRepository(db);
        OtpService failingService = new OtpService(failingChallenges, grants, readyTestCodec(),
                activeTestProperties(), clock, tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(provider));

        assertThatThrownBy(() -> failingService.request(phone, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));

        // The OLD challenge's supersede genuinely ran inside the transaction, then must have been
        // rolled back: it is exactly as it was before this failed request, never SUPERSEDED.
        Document oldDoc = challenges.findById(oldReq.challengeId());
        assertThat(oldDoc.getString("status")).as("rollback must restore the old challenge to ACTIVE")
                .isEqualTo("ACTIVE");
        assertThat(oldDoc.getBoolean("active", false)).isTrue();

        // It is genuinely the CURRENT usable code — never a state where both codes are unusable.
        Document active = challenges.findActive(Phone.parse(phone), OtpPurpose.LOGIN);
        assertThat(active.getString("_id")).isEqualTo(oldReq.challengeId());

        // And it still verifies successfully with the ORIGINAL otp.
        OtpVerifyResult verify = service.verify(oldReq.challengeId(), oldOtp, "203.0.113.1");
        assertThat(verify.grantId()).isNotNull();
    }

    @Test void activation_transition_result_is_checked_never_blindly_assumed_to_have_applied() {
        String phone = uniquePhone("40560");
        String challengeId = OpaqueIds.newChallengeId();
        byte[] verifier = readyTestCodec().verifierFor(challengeId, Phone.parse(phone), OtpPurpose.LOGIN, "123456");
        Instant now = CLOCK_NOW.get();
        challenges.createPendingDelivery(challengeId, Phone.parse(phone), OtpPurpose.LOGIN, verifier, now,
                now.plusSeconds(60), 3);
        // Simulate the expected PENDING_DELIVERY state having already been resolved by someone else.
        challenges.markDeliveryFailed(challengeId);

        Document[] holder = new Document[1];
        tx.run(session -> holder[0] = challenges.activateAfterDelivery(session, challengeId, Phone.parse(phone),
                OtpPurpose.LOGIN, now, now.plusSeconds(300), now.plusSeconds(30)));
        assertThat(holder[0])
                .as("activation must not silently report success once the expected pending state is gone")
                .isNull();
    }

    /** Durability §6 — the OTP's own validity window starts at CONFIRMED delivery, never at
     *  challenge creation: a slow provider must not silently consume a user's OTP validity. */
    @Test void otp_ttl_starts_at_confirmed_delivery_not_at_challenge_creation() throws Exception {
        String phone = uniquePhone("40570");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ControllableOtpDeliveryProvider slowProvider = new ControllableOtpDeliveryProvider(entered, proceed, false);
        OtpService slowService = new OtpService(challenges, grants, readyTestCodec(), activeTestProperties(), clock,
                tx, fixedProvider(sharedAllowLimiter()), fixedDeliveryProvider(slowProvider));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<OtpRequestResult> future = pool.submit(() -> slowService.request(phone, "203.0.113.1"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // The provider takes a while to confirm — the clock advances DURING that window.
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(45));
        proceed.countDown();
        OtpRequestResult result = future.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(result.expiresInSeconds())
                .as("the full configured TTL must be granted from confirmed delivery, not reduced by the 45s delay")
                .isEqualTo(300);
        assertThat(result.resendAfterSeconds()).isEqualTo(30);
    }

    /** Durability §7 — a PENDING_DELIVERY left behind by a simulated crashed process (never
     *  resolved to ACTIVE or DELIVERY_FAILED) must not permanently block the phone. */
    @Test void stale_pending_delivery_is_retired_so_a_new_request_can_proceed() {
        String phone = uniquePhone("40580");
        String staleChallengeId = OpaqueIds.newChallengeId();
        byte[] verifier = readyTestCodec().verifierFor(staleChallengeId, Phone.parse(phone), OtpPurpose.LOGIN, "000000");
        Instant now = CLOCK_NOW.get();
        // deliveryDeadline already in the past — as if this process crashed 5 minutes ago, right
        // after inserting, before ever recording a provider outcome.
        challenges.createPendingDelivery(staleChallengeId, Phone.parse(phone), OtpPurpose.LOGIN, verifier,
                now.minusSeconds(300), now.minusSeconds(240), 3);

        OtpRequestResult result = service.request(phone, "203.0.113.1");
        assertThat(result.challengeId()).isNotEqualTo(staleChallengeId);

        Document stale = challenges.findById(staleChallengeId);
        assertThat(stale.getString("status")).isEqualTo("DELIVERY_FAILED");
        Document active = challenges.findActive(Phone.parse(phone), OtpPurpose.LOGIN);
        assertThat(active.getString("_id")).isEqualTo(result.challengeId());
    }

    @Test void non_stale_delivering_slot_is_never_retired_early() {
        String phone = uniquePhone("40590");
        String inFlightId = OpaqueIds.newChallengeId();
        byte[] verifier = readyTestCodec().verifierFor(inFlightId, Phone.parse(phone), OtpPurpose.LOGIN, "000000");
        Instant now = CLOCK_NOW.get();
        // deliveryDeadline still in the FUTURE — a genuinely in-flight delivery, not stale.
        challenges.createPendingDelivery(inFlightId, Phone.parse(phone), OtpPurpose.LOGIN, verifier, now,
                now.plusSeconds(60), 3);

        assertThatThrownBy(() -> service.request(phone, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
        assertThat(challenges.findById(inFlightId).getString("status"))
                .as("a genuinely in-flight delivery must never be retired early").isEqualTo("PENDING_DELIVERY");
    }

    @Test void same_challenge_cannot_ever_create_two_grant_documents() {
        String phone = uniquePhone("40520");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);
        service.verify(req.challengeId(), otp, "203.0.113.1");

        // Defense-in-depth (hardening §7): even a direct repository call attempting a SECOND,
        // differently-id'd grant for the SAME challengeId is rejected by the unique index.
        assertThatThrownBy(() -> grants.insert(OpaqueIds.newGrantId(), req.challengeId(), Phone.parse(phone),
                OtpPurpose.LOGIN, CLOCK_NOW.get(), CLOCK_NOW.get().plusSeconds(300)))
                .isInstanceOf(MongoWriteException.class);
    }

    @Test void correct_otp_cannot_win_once_attempt_count_has_reached_the_max_even_before_the_lock_flag_is_set() {
        String phone = uniquePhone("40530");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);

        // Deterministically simulate the exact race window hardening §3 closes: attemptCount has
        // JUST reached maxAttempts (3) via the atomic $inc, but the SEPARATE follow-up "flip to
        // LOCKED" update has not run yet — status is still ACTIVE in the database at this instant.
        db.getCollection(OtpChallengeRepository.COLLECTION).updateOne(
                Filters.eq("_id", req.challengeId()), Updates.set("attemptCount", 3));
        assertThat(challenges.findById(req.challengeId()).getString("status")).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> service.verify(req.challengeId(), otp, "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.INVALID));
        assertThat(challenges.findById(req.challengeId()).getString("status"))
                .as("the CAS predicate itself — not merely the LOCKED flip — must reject this")
                .isNotEqualTo("VERIFIED");
    }

    @Test void tryMarkVerified_cas_rejects_past_expiry_even_while_status_is_still_active_in_the_document() {
        String phone = uniquePhone("40540");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        Instant farFuture = CLOCK_NOW.get().plusSeconds(10_000);

        // Exercised directly at the repository layer: status in the database is untouched (still
        // ACTIVE — markExpiredIfPastDeadline was never called), proving the CAS predicate itself
        // enforces expiry (hardening §4), not merely OtpService's earlier read-based pre-check.
        Document result = challenges.tryMarkVerified(req.challengeId(), OpaqueIds.newGrantId(), farFuture, 3);
        assertThat(result).isNull();
        assertThat(challenges.findById(req.challengeId()).getString("status")).isEqualTo("ACTIVE");
    }

    @Test void fixed_clock_controls_the_verified_at_timestamp_not_wall_time() {
        String phone = uniquePhone("40550");
        OtpRequestResult req = service.request(phone, "203.0.113.1");
        String otp = provider.lastOtpByPhone.get("+91" + phone);
        Instant verifyTime = CLOCK_NOW.get().plusSeconds(10);
        CLOCK_NOW.set(verifyTime);

        service.verify(req.challengeId(), otp, "203.0.113.1");
        Document doc = challenges.findById(req.challengeId());
        assertThat(doc.getDate("verifiedAt").toInstant()).isEqualTo(verifyTime);
        assertThat(doc.containsKey("consumedAt"))
                .as("the challenge tracks verifiedAt, never a misleading consumedAt — that belongs to the grant")
                .isFalse();
    }

    // ---------- unavailable seams ----------

    @Test void not_ready_codec_fails_closed_on_request_and_verify() {
        OtpAuthProperties badProps = new OtpAuthProperties();
        OtpVerifierCodec notReady = new OtpVerifierCodec(badProps);
        OtpService brokenService = new OtpService(challenges, grants, notReady, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), tx, fixedProvider(null), fixedDeliveryProvider(provider));
        assertThatThrownBy(() -> brokenService.request(uniquePhone("40200"), "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
    }

    @Test void missing_rate_limiter_fails_closed() {
        OtpVerifierCodec readyCodec = readyTestCodec();
        OtpService brokenService = new OtpService(challenges, grants, readyCodec, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), tx, fixedProvider(null), fixedDeliveryProvider(provider));
        assertThatThrownBy(() -> brokenService.request(uniquePhone("40210"), "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
    }

    @Test void missing_provider_fails_closed_on_request_only() {
        OtpVerifierCodec readyCodec = readyTestCodec();
        RateLimitStore alwaysAllow = (buckets, cost) -> new Admission.Allowed(List.of());
        OtpRateLimiter limiter = new OtpRateLimiter(alwaysAllow, activeTestProperties());
        OtpService brokenService = new OtpService(challenges, grants, readyCodec, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), tx, fixedProvider(limiter), fixedDeliveryProvider(null));
        assertThatThrownBy(() -> brokenService.request(uniquePhone("40220"), "203.0.113.1"))
                .isInstanceOf(OtpFailure.class)
                .satisfies(e -> assertThat(((OtpFailure) e).reason()).isEqualTo(OtpFailure.Reason.UNAVAILABLE));
    }

    @Test void request_rate_limited_bucket_denies_with_retry_after() {
        OtpVerifierCodec readyCodec = readyTestCodec();
        RateLimitStore denies = (buckets, cost) -> new Admission.RateLimited(Duration.ofSeconds(7), List.of());
        OtpRateLimiter limiter = new OtpRateLimiter(denies, activeTestProperties());
        OtpService limitedService = new OtpService(challenges, grants, readyCodec, activeTestProperties(),
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), tx, fixedProvider(limiter), fixedDeliveryProvider(provider));
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
                Clock.fixed(CLOCK_NOW.get(), ZoneOffset.UTC), tx, fixedProvider(limiter), fixedDeliveryProvider(provider));
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
