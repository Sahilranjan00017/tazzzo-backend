package com.tazzzo.auth.session;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.tazzzo.auth.CustomerAccessTokenCodec;
import com.tazzzo.auth.CustomerId;
import com.tazzzo.auth.CustomerPrincipal;
import com.tazzzo.auth.SessionId;
import com.tazzzo.auth.otp.OtpPurpose;
import com.tazzzo.auth.otp.OtpVerifiedGrantRepository;
import com.tazzzo.auth.otp.Phone;
import com.tazzzo.catalog.AbstractMongoIT;
import com.tazzzo.catalog.CatalogApplication;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-11C — {@code CustomerSessionService} exercised over a real Mongo (Testcontainers) and a real
 * {@code Tx} (multi-document transaction). No sleeps anywhere: concurrency is pinned with latches,
 * time with a mutable {@link Clock}, and failure injection with repository subclasses — the SAME
 * conventions {@code OtpServiceIT} established.
 */
@SpringBootTest(classes = {CatalogApplication.class, CustomerSessionServiceIT.TestBeans.class})
class CustomerSessionServiceIT extends AbstractMongoIT {

    static final String ACCESS_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    // Deliberately DIFFERENT from ACCESS_KEY — Finding 3's key-separation invariant rejects
    // startup if the access-token and refresh-token domains share the same secret material.
    static final String REFRESH_KEY = Base64.getEncoder().encodeToString(fill((byte) 1));
    // Deliberately in the FUTURE: documents written with this clock land in TTL-indexed collections
    // (expireAfter 0 on expiresAt). A base in the real past makes the Mongo TTL monitor delete them
    // mid-test (flaky EXPIRED->INVALID). Must stay later than any real wall-clock time CI can reach.
    static final AtomicReference<Instant> CLOCK_NOW = new AtomicReference<>(Instant.parse("2099-06-01T00:00:00Z"));

    private static byte[] fill(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tazzzo.customer-auth.access-token-hmac-key-b64", () -> ACCESS_KEY);
        r.add("tazzzo.customer-auth.session.refresh-token-hmac-key-b64", () -> REFRESH_KEY);
        r.add("tazzzo.customer-auth.session.access-token-ttl-seconds", () -> "900");
        r.add("tazzzo.customer-auth.session.session-ttl-seconds", () -> "2592000");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return this; }
                @Override public Instant instant() { return CLOCK_NOW.get(); }
            };
        }
    }

    /** Fails the SESSION-based create() call once, then delegates to the real behavior. */
    static class FailOnceSessionRepository extends CustomerSessionRepository {
        private volatile boolean failed = false;

        FailOnceSessionRepository(MongoDatabase db) {
            super(db);
        }

        @Override
        public Document create(ClientSession session, SessionId sessionId, CustomerId customerId, Instant now,
                               Instant expiresAt, byte[] refreshDigest) {
            if (!failed) {
                failed = true;
                throw new RuntimeException("simulated transient session-create failure");
            }
            return super.create(session, sessionId, customerId, now, expiresAt, refreshDigest);
        }
    }

    @Autowired CustomerSessionService service;
    @Autowired OtpVerifiedGrantRepository grants;
    @Autowired CustomerRepository customers;
    @Autowired CustomerSessionRepository sessions;
    @Autowired RefreshTokenCodec refreshCodec;
    @Autowired CustomerAccessTokenCodec accessCodec;
    @Autowired CustomerSessionProperties properties;
    @Autowired Clock clock;
    @Autowired Tx tx;
    @Autowired SessionObservability observability;

    @BeforeEach
    void resetClock() {
        CLOCK_NOW.set(Instant.parse("2099-06-01T00:00:00Z"));
    }

    private static String uniquePhone(String suffix) {
        return "+9198765" + suffix;
    }

    private String seedGrant(String phone) {
        String grantId = "GRANT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        grants.insert(grantId, "OTP_fixture_" + grantId, new Phone(phone), OtpPurpose.LOGIN, CLOCK_NOW.get(),
                CLOCK_NOW.get().plusSeconds(300));
        return grantId;
    }

    // ---------- happy path ----------

    @Test void full_flow_grant_to_session_to_refresh_to_logout() {
        String grantId = seedGrant(uniquePhone("40001"));
        CustomerSessionService.SessionEstablishResult established = service.establishSession(grantId);
        assertThat(established.customerId()).matches("^CUS_[A-Za-z0-9_-]+$");
        assertThat(established.accessTokenExpiresIn()).isEqualTo(900);

        CustomerPrincipal principal = accessCodec.verify(established.accessToken());
        assertThat(principal.customerId().value()).isEqualTo(established.customerId());

        // Advance the clock so the refreshed access token is issued at a distinguishably later
        // instant -- with a frozen clock two tokens minted for the identical principal at the
        // identical instant are legitimately byte-for-byte identical (a deterministic function of
        // customerId/sessionId/iat/exp), which is not itself a bug.
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(5));
        CustomerSessionService.RefreshResult refreshed = service.refresh(established.refreshToken());
        assertThat(refreshed.accessToken()).isNotEqualTo(established.accessToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(established.refreshToken());

        CustomerPrincipal refreshedPrincipal = accessCodec.verify(refreshed.accessToken());
        assertThat(refreshedPrincipal.customerId().value()).isEqualTo(established.customerId());

        service.logout(refreshedPrincipal);
        assertThatThrownBy(() -> service.refresh(refreshed.refreshToken()))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    @Test void existing_customer_login_returns_the_same_customer() {
        String phone = uniquePhone("40010");
        String firstGrant = seedGrant(phone);
        CustomerSessionService.SessionEstablishResult first = service.establishSession(firstGrant);

        String secondGrant = seedGrant(phone);
        CustomerSessionService.SessionEstablishResult second = service.establishSession(secondGrant);

        assertThat(second.customerId()).isEqualTo(first.customerId());
    }

    @Test void grant_response_never_exposes_phone() {
        // Structural: SessionEstablishResult/response DTOs simply have no phone field. Verify the
        // established customer record IS phone-linked server-side without ever surfacing it.
        String phone = uniquePhone("40015");
        String grantId = seedGrant(phone);
        CustomerSessionService.SessionEstablishResult result = service.establishSession(grantId);
        Document customer = customers.findById(result.customerId());
        assertThat(customer.getString("phoneNormalized")).isEqualTo(phone);
    }

    // ---------- grant validation ----------

    @Test void unknown_grant_is_generic_invalid() {
        assertThatThrownBy(() -> service.establishSession("GRANT_doesnotexist"))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    @Test void reused_grant_is_generic_invalid() {
        String grantId = seedGrant(uniquePhone("40020"));
        service.establishSession(grantId);
        assertThatThrownBy(() -> service.establishSession(grantId))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    @Test void expired_grant_is_generic_invalid() {
        String grantId = "GRANT_expiredfixture000001";
        grants.insert(grantId, "OTP_fixture_expired", new Phone(uniquePhone("40025")), OtpPurpose.LOGIN,
                CLOCK_NOW.get().minusSeconds(600), CLOCK_NOW.get().minusSeconds(300));
        assertThatThrownBy(() -> service.establishSession(grantId))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    /** Concurrency item I — a grant issued for a DIFFERENT purpose must never establish a session. */
    @Test void wrong_purpose_grant_is_generic_invalid() {
        String grantId = "GRANT_wrongpurpose0000001";
        db.getCollection(OtpVerifiedGrantRepository.COLLECTION).insertOne(new Document("_id", grantId)
                .append("challengeId", "OTP_fixture_wrongpurpose")
                .append("phoneNormalized", uniquePhone("40030"))
                .append("purpose", "SOME_FUTURE_PURPOSE")
                .append("createdAt", CLOCK_NOW.get())
                .append("expiresAt", CLOCK_NOW.get().plusSeconds(300))
                .append("consumedAt", null));
        assertThatThrownBy(() -> service.establishSession(grantId))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    @Test void malformed_grant_id_is_invalid_request() {
        assertThatThrownBy(() -> service.establishSession(""))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason())
                        .isEqualTo(SessionAuthFailure.Reason.INVALID_REQUEST));
        assertThatThrownBy(() -> service.establishSession(null))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason())
                        .isEqualTo(SessionAuthFailure.Reason.INVALID_REQUEST));
    }

    // ---------- refresh ----------

    @Test void refresh_digest_mismatch_is_generic_invalid() {
        String grantId = seedGrant(uniquePhone("40040"));
        CustomerSessionService.SessionEstablishResult established = service.establishSession(grantId);
        String sessionPrefix = established.refreshToken().split("\\.")[0];
        String tampered = sessionPrefix + ".completely-different-secret-value-xyz";
        assertThatThrownBy(() -> service.refresh(tampered))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    @Test void malformed_refresh_token_shape_is_invalid_request() {
        assertThatThrownBy(() -> service.refresh("not-a-refresh-token"))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason())
                        .isEqualTo(SessionAuthFailure.Reason.INVALID_REQUEST));
    }

    @Test void refresh_after_logout_is_rejected() {
        String grantId = seedGrant(uniquePhone("40050"));
        CustomerSessionService.SessionEstablishResult established = service.establishSession(grantId);
        CustomerPrincipal principal = accessCodec.verify(established.accessToken());
        service.logout(principal);
        assertThatThrownBy(() -> service.refresh(established.refreshToken()))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    @Test void logout_is_idempotent() {
        String grantId = seedGrant(uniquePhone("40055"));
        CustomerSessionService.SessionEstablishResult established = service.establishSession(grantId);
        CustomerPrincipal principal = accessCodec.verify(established.accessToken());
        service.logout(principal);
        service.logout(principal); // must not throw
    }

    @Test void session_expired_refresh_fails() {
        String grantId = seedGrant(uniquePhone("40060"));
        CustomerSessionService.SessionEstablishResult established = service.establishSession(grantId);
        CLOCK_NOW.set(CLOCK_NOW.get().plusSeconds(properties.getSessionTtlSeconds() + 10));
        assertThatThrownBy(() -> service.refresh(established.refreshToken()))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    @Test void a_rotated_refresh_token_can_no_longer_be_reused() {
        String grantId = seedGrant(uniquePhone("40065"));
        CustomerSessionService.SessionEstablishResult established = service.establishSession(grantId);
        service.refresh(established.refreshToken()); // rotates
        assertThatThrownBy(() -> service.refresh(established.refreshToken()))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID));
    }

    // ---------- concurrency ----------

    /** Item A — the SAME grant submitted concurrently must establish exactly one session. */
    @Test void same_grant_submitted_concurrently_establishes_exactly_one_session() throws Exception {
        String grantId = seedGrant(uniquePhone("40100"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<CustomerSessionService.SessionEstablishResult> successes = new CopyOnWriteArrayList<>();
        List<SessionAuthFailure> failures = new CopyOnWriteArrayList<>();
        Runnable attempt = () -> {
            ready.countDown();
            try {
                go.await();
                successes.add(service.establishSession(grantId));
            } catch (SessionAuthFailure e) {
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

        assertThat(successes).as("exactly one racer establishes a session").hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID);
    }

    /** Item B — a first-login race for the SAME phone must yield exactly one customer. */
    @Test void first_login_customer_creation_race_yields_exactly_one_customer() throws Exception {
        String phone = uniquePhone("40110");
        String grantA = seedGrant(phone);
        String grantB = seedGrant(phone); // superseding the "at most one active OTP challenge" rule
                                          // is out of scope here -- grants are independent fixtures.

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<CustomerSessionService.SessionEstablishResult> results = new CopyOnWriteArrayList<>();
        Runnable attemptA = () -> {
            ready.countDown();
            try {
                go.await();
                results.add(service.establishSession(grantA));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable attemptB = () -> {
            ready.countDown();
            try {
                go.await();
                results.add(service.establishSession(grantB));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        pool.submit(attemptA);
        pool.submit(attemptB);
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).customerId()).isEqualTo(results.get(1).customerId());
    }

    /** Item C — the SAME refresh token used concurrently: exactly one refresh succeeds. */
    @Test void same_refresh_token_used_concurrently_exactly_one_succeeds() throws Exception {
        String grantId = seedGrant(uniquePhone("40120"));
        CustomerSessionService.SessionEstablishResult established = service.establishSession(grantId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<CustomerSessionService.RefreshResult> successes = new CopyOnWriteArrayList<>();
        List<SessionAuthFailure> failures = new CopyOnWriteArrayList<>();
        Runnable attempt = () -> {
            ready.countDown();
            try {
                go.await();
                successes.add(service.refresh(established.refreshToken()));
            } catch (SessionAuthFailure e) {
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

        assertThat(successes).as("exactly one refresh wins").hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).reason()).isEqualTo(SessionAuthFailure.Reason.INVALID);

        // exactly one new refresh token remains current
        assertThatThrownBy(() -> service.refresh(established.refreshToken())).isInstanceOf(SessionAuthFailure.class);
        CustomerSessionService.RefreshResult winner = successes.get(0);
        CustomerSessionService.RefreshResult secondRefresh = service.refresh(winner.refreshToken());
        assertThat(secondRefresh.accessToken()).isNotNull();
    }

    /** Item F — a transaction failure AFTER grant consumption (but before session creation
     *  commits) must roll back the grant consumption too; retry via the working flow succeeds. */
    @Test void transaction_failure_after_grant_consumption_rolls_back_and_retry_succeeds() {
        String phone = uniquePhone("40130");
        String grantId = seedGrant(phone);

        FailOnceSessionRepository failingSessions = new FailOnceSessionRepository(db);
        CustomerSessionService failingService = new CustomerSessionService(grants, customers, failingSessions,
                refreshCodec, accessCodec, properties, clock, tx, observability);

        assertThatThrownBy(() -> failingService.establishSession(grantId))
                .isInstanceOf(SessionAuthFailure.class)
                .satisfies(e -> assertThat(((SessionAuthFailure) e).reason()).isEqualTo(SessionAuthFailure.Reason.UNAVAILABLE));

        // The grant must NOT have been consumed -- the transaction rolled back entirely.
        CustomerSessionService.SessionEstablishResult recovered = service.establishSession(grantId);
        assertThat(recovered.customerId()).matches("^CUS_[A-Za-z0-9_-]+$");
    }
}
